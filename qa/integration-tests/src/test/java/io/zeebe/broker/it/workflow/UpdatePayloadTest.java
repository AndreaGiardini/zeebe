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
package io.zeebe.broker.it.workflow;

import static io.zeebe.broker.it.util.TopicEventRecorder.taskType;
import static io.zeebe.broker.it.util.TopicEventRecorder.wfInstanceEvent;
import static io.zeebe.broker.workflow.graph.transformer.ZeebeExtensions.wrap;
import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;

import io.zeebe.broker.it.ClientRule;
import io.zeebe.broker.it.EmbeddedBrokerRule;
import io.zeebe.broker.it.util.TopicEventRecorder;
import io.zeebe.broker.workflow.graph.transformer.ZeebeExtensions.ZeebeModelInstance;
import io.zeebe.client.cmd.ClientCommandRejectedException;
import io.zeebe.client.event.TaskEvent;
import io.zeebe.client.event.WorkflowInstanceEvent;

public class UpdatePayloadTest
{
    private static final String PAYLOAD = "{\"foo\": \"bar\"}";

    private static final ZeebeModelInstance WORKFLOW = wrap(Bpmn.createExecutableProcess("process")
                                                                .startEvent("start")
                                                                .serviceTask("task-1")
                                                                .serviceTask("task-2")
                                                                .endEvent("end")
                                                                .done())
                .taskDefinition("task-1", "task-1", 3)
                .taskDefinition("task-2", "task-2", 3)
                .ioMapping("task-1")
                    .output("$.result", "$.result")
                    .done();

    public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();
    public ClientRule clientRule = new ClientRule();
    public TopicEventRecorder eventRecorder = new TopicEventRecorder(clientRule);

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(brokerRule)
        .around(clientRule)
        .around(eventRecorder);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void init()
    {
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW)
            .execute();
    }

    @Test
    public void shouldUpdatePayloadWhenActivityIsActivated()
    {
        // given
        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("ACTIVITY_ACTIVATED")));

        final WorkflowInstanceEvent activtyInstance = eventRecorder.getSingleWorkflowInstanceEvent(wfInstanceEvent("ACTIVITY_ACTIVATED"));

        // when
        clientRule.workflows().updatePayload(activtyInstance)
            .payload(PAYLOAD)
            .execute();

        // then
        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("PAYLOAD_UPDATED")));

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("task-1")
            .lockOwner("owner")
            .lockTime(Duration.ofMinutes(5))
            .handler((c, t) -> c.completeTask("{\"result\": \"ok\"}"))
            .open();

        waitUntil(() -> eventRecorder.hasTaskEvent(taskType("task-2")));

        final TaskEvent task2 = eventRecorder.getTaskEvents(taskType("task-2")).get(0);
        assertThat(task2.getPayload()).isEqualTo("{\"foo\":\"bar\",\"result\":\"ok\"}");
    }

    @Test
    public void shouldFailUpdatePayloadIfActivityIsCompleted()
    {
        // given
        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("task-1")
            .lockOwner("owner")
            .lockTime(Duration.ofMinutes(5))
            .handler((c, t) -> c.completeTask("{\"result\": \"done\"}"))
            .open();

        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("ACTIVITY_COMPLETED")));

        final WorkflowInstanceEvent activityInstance = eventRecorder.getSingleWorkflowInstanceEvent(wfInstanceEvent("ACTIVITY_COMPLETED"));

        // then
        thrown.expect(ClientCommandRejectedException.class);
        thrown.expectMessage("Command for event with key " + activityInstance.getMetadata().getEventKey() +
                " was rejected by broker (UPDATE_PAYLOAD_REJECTED)");

        // when
        clientRule.workflows().updatePayload(activityInstance)
            .payload(PAYLOAD)
            .execute();

    }

}
