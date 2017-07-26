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

import static io.zeebe.protocol.clientapi.EventType.*;
import static io.zeebe.util.EnsureUtil.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.cmd.TaskCommand;
import io.zeebe.client.impl.Partition;
import io.zeebe.client.impl.cmd.AbstractExecuteCmdImpl;
import io.zeebe.client.impl.cmd.ClientCommandManager;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.impl.event.TopicClientImpl;
import io.zeebe.client.task.cmd.UpdateTaskRetriesCmd;

public class UpdateTaskRetriesCmdImpl extends AbstractExecuteCmdImpl<TaskCommand, Long> implements UpdateTaskRetriesCmd
{
    protected final TaskCommand taskEvent = new TaskCommand();
    protected final MsgPackConverter msgPackConverter;

    protected long taskKey = -1L;
    protected int retries = -1;
    protected String taskType;
    protected byte[] payload;
    protected Map<String, Object> headers = new HashMap<>();

    public UpdateTaskRetriesCmdImpl(final ClientCommandManager commandManager, final ObjectMapper objectMapper, MsgPackConverter msgPackConverter, final Partition topic)
    {
        super(commandManager, objectMapper, topic, TaskCommand.class, TASK_EVENT);
        this.msgPackConverter = msgPackConverter;
    }

    public UpdateTaskRetriesCmdImpl(TopicClientImpl topicClientImpl)
    {
        // TODO Auto-generated constructor stub
    }

    @Override
    public UpdateTaskRetriesCmd taskKey(long taskKey)
    {
        this.taskKey = taskKey;
        return this;
    }

    @Override
    public UpdateTaskRetriesCmd retries(int retries)
    {
        this.retries = retries;
        return this;
    }

    @Override
    public UpdateTaskRetriesCmd taskType(final String taskType)
    {
        this.taskType = taskType;
        return this;
    }

    @Override
    public UpdateTaskRetriesCmd payload(String payload)
    {
        this.payload = msgPackConverter.convertToMsgPack(payload);
        return this;
    }

    @Override
    public UpdateTaskRetriesCmd payload(InputStream payload)
    {
        this.payload = msgPackConverter.convertToMsgPack(payload);
        return this;
    }

    @Override
    public UpdateTaskRetriesCmd headers(Map<String, Object> newHeaders)
    {
        headers.clear();
        headers.putAll(newHeaders);
        return this;
    }

    @Override
    protected long getKey()
    {
        return taskKey;
    }

    @Override
    public void validate()
    {
        super.validate();
        ensureGreaterThanOrEqual("task key", taskKey, 0);
        ensureGreaterThan("retries", retries, 0);
        ensureNotNullOrEmpty("task type", taskType);
    }

    @Override
    protected Object writeCommand()
    {
        taskEvent.setEventType(TaskEventType.UPDATE_RETRIES);
        taskEvent.setType(taskType);
        taskEvent.setRetries(retries);
        taskEvent.setHeaders(headers);
        taskEvent.setPayload(payload);

        return taskEvent;
    }

    @Override
    protected void reset()
    {
        taskKey = -1L;
        retries = -1;
        taskType = null;
        payload = null;
        headers.clear();

        taskEvent.reset();
    }

    @Override
    protected Long getResponseValue(long key, TaskCommand event)
    {
        long result = -1;

        if (event.getEventType() == TaskEventType.RETRIES_UPDATED)
        {
            result = key;
        }

        return result;
    }

}
