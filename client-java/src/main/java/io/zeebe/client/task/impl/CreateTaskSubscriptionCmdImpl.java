package io.zeebe.client.task.impl;

import static io.zeebe.util.EnsureUtil.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.impl.ClientCommandManager;
import io.zeebe.client.impl.Topic;
import io.zeebe.client.impl.cmd.AbstractControlMessageCmd;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.task.impl.subscription.EventSubscriptionCreationResult;
import io.zeebe.protocol.clientapi.ControlMessageType;

public class CreateTaskSubscriptionCmdImpl extends AbstractControlMessageCmd<TaskSubscription, EventSubscriptionCreationResult>
{
    protected final TaskSubscription subscription = new TaskSubscription();

    private String taskType;
    private long lockDuration = -1L;
    private String lockOwner;
    private int initialCredits = -1;

    public CreateTaskSubscriptionCmdImpl(final ClientCommandManager commandManager, final ObjectMapper objectMapper, MsgPackConverter msgPackConverter, final Topic topic)
    {
        super(commandManager, objectMapper, topic, TaskSubscription.class, ControlMessageType.ADD_TASK_SUBSCRIPTION);
    }

    public CreateTaskSubscriptionCmdImpl lockOwner(final String lockOwner)
    {
        this.lockOwner = lockOwner;
        return this;
    }

    public CreateTaskSubscriptionCmdImpl initialCredits(final int initialCredits)
    {
        this.initialCredits = initialCredits;
        return this;
    }

    public CreateTaskSubscriptionCmdImpl lockDuration(final long lockDuration)
    {
        this.lockDuration = lockDuration;
        return this;
    }

    public CreateTaskSubscriptionCmdImpl taskType(final String taskType)
    {
        this.taskType = taskType;
        return this;
    }

    public void validate()
    {
        topic.validate();
        ensureNotNull("task type", taskType);
        ensureNotNullOrEmpty("lock owner", lockOwner);
        ensureGreaterThan("lock duration", lockDuration, 0);
        ensureGreaterThan("initial credits", initialCredits, 0);
    }

    @Override
    public void reset()
    {
        taskType = null;
        lockDuration = -1L;
        lockOwner = null;
        initialCredits = -1;
    }

    @Override
    protected Object writeCommand()
    {
        subscription.setTopicName(topic.getTopicName());
        subscription.setPartitionId(topic.getPartitionId());
        subscription.setTaskType(taskType);
        subscription.setLockDuration(lockDuration);
        subscription.setLockOwner(lockOwner);
        subscription.setCredits(initialCredits);

        return subscription;
    }

    @Override
    protected EventSubscriptionCreationResult getResponseValue(final TaskSubscription data)
    {
        return new EventSubscriptionCreationResult(data.getSubscriberKey());
    }

}
