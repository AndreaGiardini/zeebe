package io.zeebe.client.impl;

import io.zeebe.client.TaskTopicClient;
import io.zeebe.client.task.PollableTaskSubscriptionBuilder;
import io.zeebe.client.task.TaskSubscriptionBuilder;
import io.zeebe.client.task.cmd.CompleteTaskCmd;
import io.zeebe.client.task.cmd.CreateTaskCmd;
import io.zeebe.client.task.cmd.FailTaskCmd;
import io.zeebe.client.task.cmd.UpdateTaskRetriesCmd;
import io.zeebe.client.task.impl.CloseTaskSubscriptionCmdImpl;
import io.zeebe.client.task.impl.CompleteTaskCmdImpl;
import io.zeebe.client.task.impl.CreateTaskCmdImpl;
import io.zeebe.client.task.impl.CreateTaskSubscriptionCmdImpl;
import io.zeebe.client.task.impl.FailTaskCmdImpl;
import io.zeebe.client.task.impl.IncreaseTaskSubscriptionCreditsCmdImpl;
import io.zeebe.client.task.impl.UpdateTaskRetriesCmdImpl;

public class TaskTopicClientImpl implements TaskTopicClient
{

    protected final ZeebeClientImpl client;
    protected final Topic topic;

    public TaskTopicClientImpl(final ZeebeClientImpl client, final String topicName, final int partitionId)
    {
        this.client = client;
        this.topic = new Topic(topicName, partitionId);
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

    public Topic getTopic()
    {
        return topic;
    }

}
