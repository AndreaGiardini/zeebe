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
package io.zeebe.client.impl.topology;

import java.util.List;

import io.zeebe.transport.SocketAddress;

public class TopologyResponse
{
    private List<SocketAddress> brokers;

    private List<PartitionLeader> topicLeaders;

    public List<SocketAddress> getBrokers()
    {
        return brokers;
    }

    public void setBrokers(List<SocketAddress> brokers)
    {
        this.brokers = brokers;
    }

    public List<PartitionLeader> getTopicLeaders()
    {
        return topicLeaders;
    }

    public void setTopicLeaders(List<PartitionLeader> topicLeaders)
    {
        this.topicLeaders = topicLeaders;
    }
}
