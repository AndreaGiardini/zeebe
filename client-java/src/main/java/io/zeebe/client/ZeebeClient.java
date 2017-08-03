/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client;

import java.util.Properties;

import io.zeebe.client.clustering.impl.TopologyResponse;
import io.zeebe.client.impl.ZeebeClientImpl;
import io.zeebe.client.task.cmd.Request;

public interface ZeebeClient extends AutoCloseable
{
    // TODO: javadoc
    TasksClient tasks();

    /**
     * Provides APIs specific to topics of type <code>workflow</code>.
     *
     * @param topicName
     *              the name of the topic
     *
     * @param partitionId
     *            the id of the topic partition
     */
    WorkflowsClient workflows();

    /**
     * Provides general purpose APIs for any kind of topic.
     *
     * @param topicName
     *              the name of the topic
     *
     * @param partitionId
     *            the id of the topic partition
     */
    TopicsClient topics();

    // TODO: this exposes an impl class as its result
    Request<TopologyResponse> requestTopology();

    /**
     * Connects the client to the configured broker. Not thread-safe.
     */
    void connect();

    /**
     * Disconnects the client from the configured broker. Not thread-safe.
     */
    void disconnect();

    @Override
    void close();

    static ZeebeClient create(Properties properties)
    {
        return new ZeebeClientImpl(properties);
    }

}
