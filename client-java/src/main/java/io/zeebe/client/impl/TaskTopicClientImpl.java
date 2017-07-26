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
package io.zeebe.client.impl;

import io.zeebe.client.TaskTopicClient;
import io.zeebe.client.impl.task.*;
import io.zeebe.client.task.PollableTaskSubscriptionBuilder;
import io.zeebe.client.task.TaskSubscriptionBuilder;
import io.zeebe.client.task.cmd.CompleteTaskCmd;
import io.zeebe.client.task.cmd.CreateTaskCmd;
import io.zeebe.client.task.cmd.FailTaskCmd;
import io.zeebe.client.task.cmd.UpdateTaskRetriesCmd;

public class TaskTopicClientImpl implements TaskTopicClient
{

    protected final ZeebeClientImpl client;
    protected final Partition topic;

    public TaskTopicClientImpl(final ZeebeClientImpl client, final String topicName, final int partitionId)
    {
        this.client = client;
        this.topic = new Partition(topicName, partitionId);
    }

    @Override
    public CreateTaskCmd create()
    {
        return new CreateTaskCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    @Override
    public FailTaskCmd fail()
    {
        return new FailTaskCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    @Override
    public UpdateTaskRetriesCmd updateRetries()
    {
        return new UpdateTaskRetriesCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    @Override
    public CompleteTaskCmd complete()
    {
        return new CompleteTaskCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    @Override
    public TaskSubscriptionBuilder newTaskSubscription()
    {
        return client.getSubscriptionManager().newTaskSubscription(this);
    }

    @Override
    public PollableTaskSubscriptionBuilder newPollableTaskSubscription()
    {
        return client.getSubscriptionManager().newPollableTaskSubscription(this);
    }

    public CreateTaskSubscriptionCmdImpl brokerTaskSubscription()
    {
        return new CreateTaskSubscriptionCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    public CloseTaskSubscriptionCmdImpl closeBrokerTaskSubscription()
    {
        return new CloseTaskSubscriptionCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    public IncreaseTaskSubscriptionCreditsCmdImpl increaseSubscriptionCredits()
    {
        return new IncreaseTaskSubscriptionCreditsCmdImpl(client.getCommandManager(), client.getObjectMapper(), client.getMsgPackConverter(), topic);
    }

    public Partition getTopic()
    {
        return topic;
    }

}
