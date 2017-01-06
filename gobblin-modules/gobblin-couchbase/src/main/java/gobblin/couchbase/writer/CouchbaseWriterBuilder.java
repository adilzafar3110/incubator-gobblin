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

package gobblin.couchbase.writer;

import java.io.IOException;
import java.util.Properties;

import com.couchbase.client.java.env.CouchbaseEnvironment;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.typesafe.config.Config;

import gobblin.configuration.State;
import gobblin.util.ConfigUtils;
import gobblin.writer.DataWriter;
import gobblin.writer.DataWriterBuilder;




public class CouchbaseWriterBuilder extends DataWriterBuilder {
  @Override
  public DataWriter build()
      throws IOException {
    State state = this.destination.getProperties();
    Properties taskProps = state.getProperties();
    Config config = ConfigUtils.propertiesToConfig(taskProps);
    CouchbaseEnvironment couchbaseEnvironment = DefaultCouchbaseEnvironment.create();
    return new CouchbaseWriter(couchbaseEnvironment, config);
  }
}
