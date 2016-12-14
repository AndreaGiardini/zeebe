package org.camunda.tngp.client.impl.cmd;

import org.camunda.tngp.client.cmd.CreateAsyncTaskCmd;
import org.camunda.tngp.client.impl.ClientCmdExecutor;
import org.camunda.tngp.client.impl.cmd.taskqueue.CreateTaskRequestWriter;
import org.camunda.tngp.client.impl.data.DocumentConverter;
import org.camunda.tngp.util.buffer.PayloadRequestWriter;

public class CreateTaskCmdImpl extends AbstractSetPayloadCmd<Long, CreateAsyncTaskCmd>
    implements CreateAsyncTaskCmd
{
    protected final CreateTaskRequestWriter requestWriter = new CreateTaskRequestWriter();

    public CreateTaskCmdImpl(final ClientCmdExecutor clientCmdExecutor, DocumentConverter documentConverter)
    {
        super(clientCmdExecutor, new TaskAckResponseHandler(), documentConverter);
        requestWriter.resourceId(0); // TODO
    }

    @Override
    public CreateTaskCmdImpl taskQueueId(final int taskQueueId)
    {
        requestWriter.resourceId(taskQueueId);
        return this;
    }

    @Override
    public CreateAsyncTaskCmd taskType(final String taskType)
    {
        requestWriter.taskType(taskType.getBytes(CHARSET));
        return this;
    }

    @Override
    public PayloadRequestWriter getRequestWriter()
    {
        return requestWriter;
    }

}
