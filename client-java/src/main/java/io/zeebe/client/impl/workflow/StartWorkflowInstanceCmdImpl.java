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
package io.zeebe.client.impl.workflow;

import static io.zeebe.util.EnsureUtil.*;

import java.io.InputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ClientCommandRejectedException;
import io.zeebe.client.impl.Partition;
import io.zeebe.client.impl.cmd.AbstractExecuteCmdImpl;
import io.zeebe.client.impl.cmd.ClientCommandManager;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.impl.event.TopicClientImpl;
import io.zeebe.client.workflow.cmd.StartWorkflowInstanceCmd;
import io.zeebe.client.workflow.cmd.WorkflowInstance;
import io.zeebe.protocol.clientapi.EventType;

/**
 * Represents a command to create a workflow instance.
 */
public class StartWorkflowInstanceCmdImpl extends AbstractExecuteCmdImpl<WorkflowInstanceEvent, WorkflowInstance> implements StartWorkflowInstanceCmd
{
    private static final String EXCEPTION_MSG = "Failed to create instance of workflow with BPMN process id '%s' and version '%d'.";

    private final WorkflowInstanceEvent workflowInstanceEvent = new WorkflowInstanceEvent();
    protected final MsgPackConverter msgPackConverter;

    public StartWorkflowInstanceCmdImpl(final ClientCommandManager commandManager, final ObjectMapper objectMapper, MsgPackConverter msgPackConverter, final Partition topic)
    {
        super(commandManager, objectMapper, topic, WorkflowInstanceEvent.class, EventType.WORKFLOW_EVENT);
        this.msgPackConverter = msgPackConverter;
    }

    public StartWorkflowInstanceCmdImpl(TopicClientImpl topicClientImpl)
    {
        // TODO Auto-generated constructor stub
    }

    @Override
    public StartWorkflowInstanceCmd payload(final InputStream payload)
    {
        this.workflowInstanceEvent.setPayload(msgPackConverter.convertToMsgPack(payload));
        return this;
    }

    @Override
    public StartWorkflowInstanceCmd payload(final String payload)
    {
        this.workflowInstanceEvent.setPayload(msgPackConverter.convertToMsgPack(payload));
        return this;
    }

    @Override
    public StartWorkflowInstanceCmd bpmnProcessId(final String id)
    {
        this.workflowInstanceEvent.setBpmnProcessId(id);
        return this;
    }

    @Override
    public StartWorkflowInstanceCmd version(final int version)
    {
        this.workflowInstanceEvent.setVersion(version);
        return this;
    }

    @Override
    public StartWorkflowInstanceCmd latestVersion()
    {
        return version(LATEST_VERSION);
    }

    @Override
    protected Object writeCommand()
    {
        this.workflowInstanceEvent.setEventType(WorkflowInstanceEventType.CREATE_WORKFLOW_INSTANCE);
        return workflowInstanceEvent;
    }

    @Override
    protected long getKey()
    {
        return -1;
    }

    @Override
    protected void reset()
    {
        this.workflowInstanceEvent.reset();
    }

    @Override
    protected WorkflowInstance getResponseValue(final long key, final WorkflowInstanceEvent event)
    {
        if (event.getEventType() == WorkflowInstanceEventType.WORKFLOW_INSTANCE_REJECTED)
        {
            throw new ClientCommandRejectedException(String.format(EXCEPTION_MSG, event.getBpmnProcessId(), event.getVersion()));
        }
        return new WorkflowInstanceImpl(event);
    }

    @Override
    public void validate()
    {
        super.validate();
        ensureNotNullOrEmpty("bpmnProcessId", workflowInstanceEvent.getBpmnProcessId());
        ensureGreaterThanOrEqual("version", workflowInstanceEvent.getVersion(), LATEST_VERSION);
    }
}
