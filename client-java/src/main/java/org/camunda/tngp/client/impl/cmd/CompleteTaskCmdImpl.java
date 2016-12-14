package org.camunda.tngp.client.impl.cmd;

import org.camunda.tngp.client.cmd.CompleteAsyncTaskCmd;
import org.camunda.tngp.client.impl.ClientCmdExecutor;
import org.camunda.tngp.client.impl.cmd.taskqueue.CompleteTaskRequestWriter;
import org.camunda.tngp.client.impl.data.DocumentConverter;
import org.camunda.tngp.util.buffer.PayloadRequestWriter;

public class CompleteTaskCmdImpl extends AbstractSetPayloadCmd<Long, CompleteAsyncTaskCmd>
    implements CompleteAsyncTaskCmd
{
    protected CompleteTaskRequestWriter requestWriter = new CompleteTaskRequestWriter();

    public CompleteTaskCmdImpl(final ClientCmdExecutor clientCmdExecutor, DocumentConverter documentConverter)
    {
        super(clientCmdExecutor, new TaskAckResponseHandler(), documentConverter);
    }

    @Override
    public CompleteAsyncTaskCmd taskId(long taskId)
    {
        requestWriter.taskId(taskId);
        return this;
    }

    @Override
    public CompleteAsyncTaskCmd taskQueueId(int taskQueueId)
    {
        requestWriter.resourceId(taskQueueId);
        return this;
    }

    public void setRequestWriter(CompleteTaskRequestWriter requestWriter)
    {
        this.requestWriter = requestWriter;
    }

    @Override
    public PayloadRequestWriter getRequestWriter()
    {
        return requestWriter;
    }
}
