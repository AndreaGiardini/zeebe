package io.zeebe.client.task.impl;

import static io.zeebe.util.EnsureUtil.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.impl.ClientCommandManager;
import io.zeebe.client.impl.Topic;
import io.zeebe.client.impl.cmd.AbstractControlMessageWithoutResponseCmd;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.protocol.clientapi.ControlMessageType;

public class CloseTaskSubscriptionCmdImpl extends AbstractControlMessageWithoutResponseCmd<TaskSubscription>
{
    protected final TaskSubscription subscription = new TaskSubscription();
    protected final MsgPackConverter msgPackConverter;

    private long subscriptionId = -1L;

    public CloseTaskSubscriptionCmdImpl(final ClientCommandManager commandManager, final ObjectMapper objectMapper, MsgPackConverter msgPackConverter, final Topic topic)
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
