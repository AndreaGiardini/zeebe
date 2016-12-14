package org.camunda.tngp.client.impl.cmd;

import java.time.Instant;
import java.util.Iterator;

import org.agrona.DirectBuffer;
import org.camunda.tngp.client.cmd.LockedTasksBatch;
import org.camunda.tngp.client.impl.data.DocumentConverter;
import org.camunda.tngp.protocol.taskqueue.LockedTaskBatchDecoder;
import org.camunda.tngp.protocol.taskqueue.LockedTaskBatchDecoder.TasksDecoder;
import org.camunda.tngp.protocol.taskqueue.LockedTaskDecoder;
import org.camunda.tngp.protocol.taskqueue.MessageHeaderDecoder;

public class PollAndLockResponseHandler implements ClientResponseHandler<LockedTasksBatch>
{
    protected final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    protected final LockedTaskBatchDecoder responseDecoder = new LockedTaskBatchDecoder();

    protected DocumentConverter documentConverter;

    public PollAndLockResponseHandler(DocumentConverter documentConverter)
    {
        this.documentConverter = documentConverter;
    }

    @Override
    public int getResponseSchemaId()
    {
        return LockedTaskBatchDecoder.SCHEMA_ID;
    }

    @Override
    public int getResponseTemplateId()
    {
        return LockedTaskBatchDecoder.TEMPLATE_ID;
    }

    @Override
    public LockedTasksBatch readResponse(DirectBuffer responseBuffer, int offset, int length)
    {
        final LockedTasksBatchImpl lockedTasksBatch = new LockedTasksBatchImpl();

        headerDecoder.wrap(responseBuffer, offset);

        offset += headerDecoder.encodedLength();

        responseDecoder.wrap(responseBuffer, offset, headerDecoder.blockLength(), headerDecoder.version());

        final Iterator<TasksDecoder> taskIterator = responseDecoder.tasks().iterator();
        while (taskIterator.hasNext())
        {
            final TasksDecoder tasksDecoder = taskIterator.next();
            final LockedTaskDecoder taskDecoder = tasksDecoder.task();


            final LockedTaskImpl lockedTask = new LockedTaskImpl(documentConverter);
            lockedTask.setLockTime(Instant.ofEpochMilli(taskDecoder.lockTime()));

            lockedTask.setId(taskDecoder.id());

            final long wfInstanceId = taskDecoder.wfInstanceId();
            final Long apiWfInstanceId = (wfInstanceId != LockedTaskDecoder.wfInstanceIdNullValue()) ? wfInstanceId : null;

            lockedTask.setWorkflowInstanceId(apiWfInstanceId);

            final int limit = responseDecoder.limit();
            final int limitBeforePayload = limit + TasksDecoder.taskPayloadHeaderLength();
            final int payloadLength = tasksDecoder.taskPayloadLength();
            final int limitAfterPayload = limitBeforePayload + payloadLength;

            lockedTask.setPayload(responseBuffer, limitBeforePayload, payloadLength);
            responseDecoder.limit(limitAfterPayload);

            lockedTasksBatch.addTask(lockedTask);
        }

        return lockedTasksBatch;
    }


}
