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

import static io.zeebe.util.EnsureUtil.ensureGreaterThanOrEqual;
import static io.zeebe.util.EnsureUtil.ensureNotNullOrEmpty;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import io.zeebe.client.cmd.TaskCommand;
import io.zeebe.client.impl.cmd.AbstractExecuteCmdImpl;
import io.zeebe.client.impl.event.TopicClientImpl;
import io.zeebe.client.task.cmd.CreateTaskCmd;

public class CreateTaskCmdImpl extends AbstractExecuteCmdImpl<TaskCommand, Long> implements CreateTaskCmd
{
    protected final TaskCommand taskEvent = new TaskCommand();

    protected String taskType;
    protected int retries = DEFAULT_RETRIES;
    protected byte[] payload;
    protected Map<String, Object> headers = new HashMap<>();

    public CreateTaskCmdImpl(TopicClientImpl topicClient)
    {
        super(topicClient);
    }

    @Override
    public CreateTaskCmd taskType(final String taskType)
    {
        this.taskType = taskType;
        return this;
    }

    @Override
    public CreateTaskCmd retries(int retries)
    {
        this.retries = retries;
        return this;
    }

    @Override
    public CreateTaskCmd payload(String payload)
    {
        this.payload = topicClient.getClient().getMsgPackConverter().convertToMsgPack(payload);
        return this;
    }

    @Override
    public CreateTaskCmd payload(InputStream payload)
    {
        this.payload = msgPackConverter.convertToMsgPack(payload);
        return this;
    }

    @Override
    public CreateTaskCmd addHeader(String key, Object value)
    {
        headers.put(key, value);
        return this;
    }

    @Override
    public CreateTaskCmd setHeaders(Map<String, Object> headers)
    {
        this.headers.clear();
        this.headers.putAll(headers);
        return this;
    }

    @Override
    protected long getKey()
    {
        return -1L;
    }

    @Override
    public void validate()
    {
        super.validate();
        ensureNotNullOrEmpty("task type", taskType);
        ensureGreaterThanOrEqual("retries", retries, 0);
    }

    @Override
    protected Object writeCommand()
    {
        taskEvent.setEventType(TaskEventType.CREATE);
        taskEvent.setType(taskType);
        taskEvent.setRetries(retries);
        taskEvent.setHeaders(headers);
        taskEvent.setPayload(payload);

        return taskEvent;
    }

    @Override
    protected void reset()
    {
        retries = DEFAULT_RETRIES;

        taskType = null;
        payload = null;
        headers.clear();

        taskEvent.reset();
    }

    @Override
    protected Long getResponseValue(long key, TaskCommand event)
    {
        return key;
    }

}
