/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gobblin.broker;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Predicate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Service;

import gobblin.broker.iface.ScopeType;
import gobblin.broker.iface.SharedResourceFactory;
import gobblin.broker.iface.SharedResourceKey;

import javax.annotation.Nonnull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;


/**
 * A backing cache for shared resources used by {@link SharedResourcesBrokerImpl}. Stores created objects in a guava
 * cache keyed by factory, scope, and key.
 * @param <S> the {@link ScopeType} class for the scopes topology used in this tree of brokers.
 */
@Slf4j
class DefaultBrokerCache<S extends ScopeType<S>> {

  private final Cache<RawJobBrokerKey, Object> sharedResourceCache;
  private final Cache<RawJobBrokerKey, ScopeImpl<S>> autoScopeCache;

  public DefaultBrokerCache() {
    this.sharedResourceCache = CacheBuilder.newBuilder().build();
    this.autoScopeCache = CacheBuilder.newBuilder().build();
  }

  /**
   * The key for shared resources in the cache.
   */
  @Data
  class RawJobBrokerKey {
    // Left if the key represents
    private final ScopeImpl<S> scope;
    private final String factoryName;
    private final String key;
  }

  /**
   * Get an object for the specified factory, key, and broker at the scope selected by the factory. {@link DefaultBrokerCache}
   * guarantees that calling this method from brokers with the same leaf scope will return the same object.
   */
  @SuppressWarnings(value = "unchecked")
  <T, K extends SharedResourceKey> T getAutoScoped(final SharedResourceFactory<T, K, S> factory, final K key,
      final SharedResourcesBrokerImpl<S> broker)
      throws ExecutionException {

    // figure out auto scope
    RawJobBrokerKey autoscopeCacheKey = new RawJobBrokerKey((ScopeImpl) broker.selfScope(), factory.getName(), key.toConfigurationKey());
    ScopeImpl<S> selectedScope = this.autoScopeCache.get(autoscopeCacheKey, new Callable<ScopeImpl<S>>() {
      @Override
      public ScopeImpl<S> call() throws Exception {
        return broker.getScope(factory.getAutoScope(broker, broker.getConfigView(null, key, factory.getName())));
      }
    });

    // get actual object
    return getScoped(factory, key, selectedScope, broker);
  }

  /**
   * Get an object for the specified factory, key, scope, and broker. {@link DefaultBrokerCache}
   * guarantees that calling this method for the same factory, key, and scope will return the same object.
   */
  @SuppressWarnings(value = "unchecked")
  <T, K extends SharedResourceKey> T getScoped(final SharedResourceFactory<T, K, S> factory, @Nonnull final K key,
      @Nonnull final ScopeImpl<S> scope, final SharedResourcesBrokerImpl<S> broker)
      throws ExecutionException {

    RawJobBrokerKey fullKey = new RawJobBrokerKey(scope, factory.getName(), key.toConfigurationKey());
    return (T) this.sharedResourceCache.get(fullKey, new Callable<Object>() {
      @Override
      public Object call()
          throws Exception {
        return factory.createResource(broker, broker.getConfigView(scope.getType(), key, factory.getName()));
      }
    });
  }

  /**
   * Invalidate all objects at scopes which are descendant of the input scope. Any such invalidated object that is a
   * {@link Closeable} will be closed, and any such object which is a {@link Service} will be shutdown.
   * @throws IOException
   */
  public void close(ScopeImpl<S> scope)
      throws IOException {
    List<Service> awaitShutdown = Lists.newArrayList();

    for (Map.Entry<RawJobBrokerKey, Object> entry : Maps.filterKeys(this.sharedResourceCache.asMap(),
        new ScopeIsAncestorFilter(scope)).entrySet()) {

      this.sharedResourceCache.invalidate(entry.getKey());

      Object obj = entry.getValue();

      if (obj instanceof Service) {
        ((Service) obj).stopAsync();
        awaitShutdown.add((Service) obj);
      } else if (obj instanceof Closeable) {
        try {
          ((Closeable) obj).close();
        } catch (IOException ioe) {
          log.error("Failed to close {}.", obj);
        }
      }
    }

    for (Service service : awaitShutdown) {
      try {
        service.awaitTerminated(10, TimeUnit.SECONDS);
      } catch (TimeoutException te) {
        log.error("Failed to shutdown {}.", service);
      }
    }
  }

  /**
   * Filter {@link RawJobBrokerKey} that are not descendants of the input {@link ScopeImpl}.
   */
  @AllArgsConstructor
  private class ScopeIsAncestorFilter implements Predicate<RawJobBrokerKey> {
    private final ScopeImpl<S> scope;

    @Override
    public boolean apply(RawJobBrokerKey input) {
      if (this.scope == null) {
        return true;
      }
      if (input.getScope() == null) {
        return false;
      }
      return SharedResourcesBrokerUtils.isScopeAncestor(input.getScope(), this.scope);
    }
  }
}
