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
package io.zeebe.broker.it.startup;

import static io.zeebe.broker.it.util.TopicEventRecorder.incidentEvent;
import static io.zeebe.broker.it.util.TopicEventRecorder.taskEvent;
import static io.zeebe.broker.it.util.TopicEventRecorder.wfInstanceEvent;
import static io.zeebe.broker.workflow.graph.transformer.ZeebeExtensions.wrap;
import static io.zeebe.test.util.TestUtil.doRepeatedly;
import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import java.util.regex.Pattern;

import org.assertj.core.util.Files;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import io.zeebe.broker.it.ClientRule;
import io.zeebe.broker.it.EmbeddedBrokerRule;
import io.zeebe.broker.it.util.RecordingTaskHandler;
import io.zeebe.broker.it.util.TopicEventRecorder;
import io.zeebe.broker.workflow.graph.transformer.ZeebeExtensions.ZeebeModelInstance;
import io.zeebe.client.ClientProperties;
import io.zeebe.client.event.TaskEvent;
import io.zeebe.client.event.WorkflowInstanceEvent;
import io.zeebe.client.workflow.cmd.DeploymentEvent;
import io.zeebe.test.util.TestFileUtil;
import io.zeebe.test.util.TestUtil;
import io.zeebe.util.time.ClockUtil;

public class BrokerRecoveryTest
{
    private static final ZeebeModelInstance WORKFLOW = wrap(Bpmn.createExecutableProcess("process")
                                                                .startEvent("start")
                                                                .serviceTask("task")
                                                                .endEvent("end")
                                                                .done())
           .taskDefinition("task", "foo", 3);

    private static final ZeebeModelInstance WORKFLOW_TWO_TASKS = wrap(Bpmn.createExecutableProcess("process")
                                                                          .startEvent("start")
                                                                          .serviceTask("task1")
                                                                          .serviceTask("task2")
                                                                          .endEvent("end")
                                                                          .done())
          .taskDefinition("task1", "foo", 3)
          .taskDefinition("task2", "bar", 3);

    private static final ZeebeModelInstance WORKFLOW_INCIDENT = wrap(Bpmn.createExecutableProcess("process")
                                                                         .startEvent()
                                                                         .serviceTask("task")
                                                                         .done())
           .taskDefinition("task", "test", 3)
           .ioMapping("task")
               .input("$.foo", "$.foo")
               .done();

    public TemporaryFolder tempFolder = new TemporaryFolder();

    public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule(() -> brokerConfig(tempFolder.getRoot().getAbsolutePath()));

    public ClientRule clientRule = new ClientRule(() ->
    {
        final Properties properties = new Properties();

        properties.put(ClientProperties.CLIENT_TASK_EXECUTION_AUTOCOMPLETE, false);

        return properties;
    });

    public TopicEventRecorder eventRecorder = new TopicEventRecorder(clientRule);

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(tempFolder)
        .around(brokerRule)
        .around(clientRule)
        .around(eventRecorder);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    protected static InputStream brokerConfig(String path)
    {
        final String canonicallySeparatedPath = path.replaceAll(Pattern.quote(File.separator), "/");

        return TestFileUtil.readAsTextFileAndReplace(
                BrokerRecoveryTest.class.getClassLoader().getResourceAsStream("recovery-broker.cfg.toml"),
                StandardCharsets.UTF_8,
                Collections.singletonMap("\\$\\{brokerFolder\\}", canonicallySeparatedPath));
    }

    @After
    public void cleanUp()
    {
        ClockUtil.reset();
    }

    @Test
    public void shouldCreateWorkflowInstanceAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW)
            .execute();

        // when
        restartBroker();

        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        // then
        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("WORKFLOW_INSTANCE_CREATED")));
    }

    @Test
    public void shouldContinueWorkflowInstanceAtTaskAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW)
            .execute();

        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("CREATED")));

        // when
        restartBroker();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockOwner("test")
            .lockTime(Duration.ofSeconds(5))
            .handler((c, t) -> c.completeTask())
            .open();

        // then
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("WORKFLOW_INSTANCE_COMPLETED")));
    }

    @Test
    public void shouldContinueWorkflowInstanceWithLockedTaskAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW)
            .execute();

        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        final RecordingTaskHandler recordingTaskHandler = new RecordingTaskHandler();
        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockOwner("test")
            .lockTime(Duration.ofSeconds(5))
            .handler(recordingTaskHandler)
            .open();

        waitUntil(() -> !recordingTaskHandler.getHandledTasks().isEmpty());

        // when
        restartBroker();

        final TaskEvent task = recordingTaskHandler.getHandledTasks().get(0);
        clientRule.tasks().complete(task).execute();

        // then
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("WORKFLOW_INSTANCE_COMPLETED")));
    }

    @Test
    public void shouldContinueWorkflowInstanceAtSecondTaskAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW_TWO_TASKS)
            .execute();

        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockOwner("test")
            .lockTime(Duration.ofSeconds(5))
            .handler((c, t) -> c.completeTask())
            .open();

        waitUntil(() -> eventRecorder.getTaskEvents(taskEvent("CREATED")).size() > 1);

        // when
        restartBroker();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("bar")
            .lockOwner("test")
            .lockTime(Duration.ofSeconds(5))
            .handler((c, t) -> c.completeTask())
            .open();

        // then
        waitUntil(() -> eventRecorder.getTaskEvents(taskEvent("COMPLETED")).size() > 1);
        waitUntil(() -> eventRecorder.hasWorkflowInstanceEvent(wfInstanceEvent("WORKFLOW_INSTANCE_COMPLETED")));
    }

    @Test
    public void shouldDeployNewWorkflowVersionAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW)
            .execute();

        // when
        restartBroker();

        final DeploymentEvent deploymentResult = clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW)
            .execute();

        // then
        assertThat(deploymentResult.getDeployedWorkflows().get(0).getVersion()).isEqualTo(2);

        final WorkflowInstanceEvent workflowInstanceV1 = clientRule.workflows()
            .create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .version(1)
            .execute();

        final WorkflowInstanceEvent workflowInstanceV2 = clientRule.workflows()
            .create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .latestVersion()
            .execute();

        // then
        assertThat(workflowInstanceV1.getVersion()).isEqualTo(1);
        assertThat(workflowInstanceV2.getVersion()).isEqualTo(2);
    }

    @Test
    public void shouldLockAndCompleteStandaloneTaskAfterRestart()
    {
        // given
        clientRule.tasks().create(clientRule.getDefaultTopic(), "foo").execute();

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("CREATED")));

        // when
        restartBroker();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockOwner("test")
            .lockTime(Duration.ofSeconds(5))
            .handler((c, t) -> c.completeTask())
            .open();

        // then
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
    }

    @Test
    public void shouldCompleteStandaloneTaskAfterRestart()
    {
        // given
        clientRule.tasks().create(clientRule.getDefaultTopic(), "foo").execute();

        final RecordingTaskHandler recordingTaskHandler = new RecordingTaskHandler();
        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockOwner("test")
            .lockTime(Duration.ofSeconds(5))
            .handler(recordingTaskHandler)
            .open();

        waitUntil(() -> !recordingTaskHandler.getHandledTasks().isEmpty());

        // when
        restartBroker();

        final TaskEvent task = recordingTaskHandler.getHandledTasks().get(0);
        clientRule.tasks().complete(task).execute();

        // then
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
    }

    @Test
    public void shouldNotReceiveLockedTasksAfterRestart()
    {
        // given
        clientRule.tasks().create(clientRule.getDefaultTopic(), "foo").execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();
        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockTime(Duration.ofSeconds(5))
            .lockOwner("test")
            .handler(taskHandler)
            .open();

        waitUntil(() -> !taskHandler.getHandledTasks().isEmpty());

        // when
        restartBroker();

        taskHandler.clear();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockTime(Duration.ofMinutes(10L))
            .lockOwner("test")
            .handler(taskHandler)
            .open();

        // then
        TestUtil.doRepeatedly(() -> null)
            .whileConditionHolds((o) -> taskHandler.getHandledTasks().isEmpty());

        assertThat(taskHandler.getHandledTasks()).isEmpty();
    }

    @Test
    public void shouldReceiveLockExpiredTasksAfterRestart()
    {
        // given
        clientRule.tasks().create(clientRule.getDefaultTopic(), "foo").execute();

        final RecordingTaskHandler recordingTaskHandler = new RecordingTaskHandler();
        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockTime(Duration.ofSeconds(5))
            .lockOwner("test")
            .handler(recordingTaskHandler)
            .open();

        waitUntil(() -> !recordingTaskHandler.getHandledTasks().isEmpty());

        // when
        restartBroker(() ->
        {
            final Instant now = ClockUtil.getCurrentTime();
            ClockUtil.setCurrentTime(now.plusSeconds(60));
        });

        // wait until stream processor and scheduler process the lock task event which is not re-processed on recovery
        doRepeatedly(() ->
        {
            final Instant now = ClockUtil.getCurrentTime();
            ClockUtil.setCurrentTime(now.plusSeconds(60));
            return null;
        }).until(t -> eventRecorder.hasTaskEvent(taskEvent("LOCK_EXPIRED")));

        recordingTaskHandler.clear();

        clientRule.tasks().newTaskSubscription(clientRule.getDefaultTopic())
            .taskType("foo")
            .lockTime(Duration.ofMinutes(10L))
            .lockOwner("test")
            .handler(recordingTaskHandler)
            .open();

        // then
        waitUntil(() -> !recordingTaskHandler.getHandledTasks().isEmpty());

        final TaskEvent task = recordingTaskHandler.getHandledTasks().get(0);
        clientRule.tasks().complete(task).execute();

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
    }

    @Test
    public void shouldResolveIncidentAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW_INCIDENT)
            .execute();

        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        waitUntil(() -> eventRecorder.hasIncidentEvent(incidentEvent("CREATED")));

        final WorkflowInstanceEvent activityInstance =
                eventRecorder.getSingleWorkflowInstanceEvent(TopicEventRecorder.wfInstanceEvent("ACTIVITY_READY"));

        // when
        restartBroker();

        clientRule.workflows().updatePayload(activityInstance)
            .payload("{\"foo\":\"bar\"}")
            .execute();

        // then
        waitUntil(() -> eventRecorder.hasIncidentEvent(incidentEvent("RESOLVED")));
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("CREATED")));

        assertThat(eventRecorder.getIncidentEvents(i -> true))
            .extracting("state")
            .containsExactly("CREATE", "CREATED", "RESOLVE", "RESOLVED");
    }

    @Test
    public void shouldResolveFailedIncidentAfterRestart()
    {
        // given
        clientRule.workflows().deploy(clientRule.getDefaultTopic())
            .bpmnModelInstance(WORKFLOW_INCIDENT)
            .execute();

        clientRule.workflows().create(clientRule.getDefaultTopic())
            .bpmnProcessId("process")
            .execute();

        waitUntil(() -> eventRecorder.hasIncidentEvent(incidentEvent("CREATED")));

        final WorkflowInstanceEvent activityInstance =
                eventRecorder.getSingleWorkflowInstanceEvent(TopicEventRecorder.wfInstanceEvent("ACTIVITY_READY"));

        clientRule.workflows().updatePayload(activityInstance)
            .payload("{\"x\":\"y\"}")
            .execute();

        waitUntil(() -> eventRecorder.hasIncidentEvent(incidentEvent("RESOLVE_FAILED")));

        // when
        restartBroker();

        clientRule.workflows().updatePayload(activityInstance)
            .payload("{\"foo\":\"bar\"}")
            .execute();

        // then
        waitUntil(() -> eventRecorder.hasIncidentEvent(incidentEvent("RESOLVED")));
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("CREATED")));

        assertThat(eventRecorder.getIncidentEvents(i -> true))
            .extracting("state")
            .containsExactly("CREATE", "CREATED", "RESOLVE", "RESOLVE_FAILED", "RESOLVE", "RESOLVED");
    }

    protected void restartBroker()
    {
        restartBroker(() ->
        { });
    }

    protected void restartBroker(Runnable onStop)
    {
        eventRecorder.stopRecordingEvents();
        clientRule.getClient().disconnect();
        brokerRule.stopBroker();

        // delete snapshot files to trigger recovery
        final File configDir = new File(tempFolder.getRoot().getAbsolutePath());
        final File snapshotDir = new File(configDir, "snapshot");

        assertThat(snapshotDir).exists().isDirectory();

        Files.delete(snapshotDir);

        onStop.run();

        brokerRule.startBroker();
        clientRule.getClient().connect();
        eventRecorder.startRecordingEvents();
    }

}
