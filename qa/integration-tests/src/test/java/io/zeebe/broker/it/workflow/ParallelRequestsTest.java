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

import static org.assertj.core.api.Assertions.assertThat;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import io.zeebe.broker.it.ClientRule;
import io.zeebe.broker.it.EmbeddedBrokerRule;
import io.zeebe.broker.it.util.ParallelRequests;
import io.zeebe.broker.it.util.ParallelRequests.SilentFuture;
import io.zeebe.client.WorkflowsClient;
import io.zeebe.client.event.WorkflowInstanceEvent;
import io.zeebe.client.workflow.cmd.DeploymentEvent;
import io.zeebe.test.util.TestUtil;

@Ignore
public class ParallelRequestsTest
{
    private static final BpmnModelInstance MODEL =
            Bpmn.createExecutableProcess("process").startEvent().endEvent().done();

    public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();

    public ClientRule clientRule = new ClientRule();

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(brokerRule)
        .around(clientRule);

    @Before
    public void deployModelInstance()
    {
        final WorkflowsClient workflowService = clientRule.workflows();

        workflowService.deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(MODEL)
            .execute();
    }

    /**
     * Smoke test for whether responses get mixed up
     */
    @Test
    public void shouldHandleParallelDeploymentAndInstantiation()
    {
        // given
        final ParallelRequests parallelRequests = ParallelRequests.prepare();

        final WorkflowsClient workflowsClient = clientRule.workflows();

        final SilentFuture<WorkflowInstanceEvent> instantiationFuture =
                parallelRequests.submitRequest(
                    () ->
                    TestUtil.doRepeatedly(() ->
                        clientRule.workflows()
                            .create(clientRule.getDefaultTopic())
                            .bpmnProcessId("foo")
                            .execute())
                        .until(
                            (wfInstance) -> wfInstance != null,
                            (exception) -> !exception.getMessage().contains("(1-3)")));

        final SilentFuture<DeploymentEvent> deploymentFuture =
                parallelRequests.submitRequest(
                    () ->
                    {
                        Thread.sleep(10);
                        return workflowsClient.deploy(clientRule.getDefaultTopic())
                            .bpmnModelInstance(MODEL)
                            .execute();
                    });

        // when
        parallelRequests.execute();

        // then
        assertThat(deploymentFuture.get()).isNotNull();
        assertThat(instantiationFuture.get()).isNotNull();

    }

}
