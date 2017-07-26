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
package io.zeebe.client.impl.task;

import static io.zeebe.util.EnsureUtil.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.impl.Partition;
import io.zeebe.client.impl.cmd.AbstractControlMessageWithoutResponseCmd;
import io.zeebe.client.impl.cmd.ClientCommandManager;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.protocol.clientapi.ControlMessageType;

public class CloseTaskSubscriptionCmdImpl extends AbstractControlMessageWithoutResponseCmd<TaskSubscription>
{
    protected final TaskSubscription subscription = new TaskSubscription();
    protected final MsgPackConverter msgPackConverter;

    private long subscriptionId = -1L;

    public CloseTaskSubscriptionCmdImpl(final ClientCommandManager commandManager, final ObjectMapper objectMapper, MsgPackConverter msgPackConverter, final Partition topic)
    {
        super(commandManager, objectMapper, topic, TaskSubscription.class, ControlMessageType.REMOVE_TASK_SUBSCRIPTION);
        this.msgPackConverter = msgPackConverter;
    }

    public CloseTaskSubscriptionCmdImpl subscriptionId(long subscriptionId)
    {
        this.subscriptionId = subscriptionId;
        return this;
    }

    public void validate()
    {
        ensureGreaterThanOrEqual("subscription id", subscriptionId, 0);
        topic.validate();
    }

    @Override
    public void reset()
    {
        subscriptionId = -1L;
    }

    @Override
    protected Object writeCommand()
    {
        subscription.setSubscriberKey(subscriptionId);
        subscription.setTopicName(topic.getTopicName());
        subscription.setPartitionId(topic.getPartitionId());

        return subscription;
    }

}
