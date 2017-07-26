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

import io.zeebe.client.impl.task.subscription.MsgPackField;
import io.zeebe.client.workflow.cmd.WorkflowInstance;

/**
 * Represents an instance of an deployed workflow, also called workflow instance.
 */
public class WorkflowInstanceImpl implements WorkflowInstance
{
    protected String bpmnProcessId;
    protected long workflowInstanceKey;
    protected int version;
    protected MsgPackField payload = new MsgPackField();

    public WorkflowInstanceImpl(WorkflowInstanceEvent event)
    {
        this.bpmnProcessId = event.getBpmnProcessId();
        this.version = event.getVersion();
        this.workflowInstanceKey = event.getWorkflowInstanceKey();
        this.payload.setMsgPack(event.getPayload());
    }

    @Override
    public String getBpmnProcessId()
    {
        return bpmnProcessId;
    }

    public void setBpmnProcessId(String bpmnProcessId)
    {
        this.bpmnProcessId = bpmnProcessId;
    }

    @Override
    public long getWorkflowInstanceKey()
    {
        return workflowInstanceKey;
    }

    public void setWorkflowInstanceKey(long workflowInstanceKey)
    {
        this.workflowInstanceKey = workflowInstanceKey;
    }

    @Override
    public int getVersion()
    {
        return version;
    }

    public void setVersion(int version)
    {
        this.version = version;
    }

    @Override
    public String getPayload()
    {
        return payload.getAsJson();
    }

    public void setPayload(String payload)
    {
        this.payload.setJson(payload);
    }
}
