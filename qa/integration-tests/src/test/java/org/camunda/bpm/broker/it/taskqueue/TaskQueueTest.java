package org.camunda.bpm.broker.it.taskqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.camunda.bpm.broker.it.ClientRule;
import org.camunda.bpm.broker.it.EmbeddedBrokerRule;
import org.camunda.bpm.broker.it.util.ParallelRequests;
import org.camunda.bpm.broker.it.util.ParallelRequests.SilentFuture;
import org.camunda.bpm.model.xml.impl.util.IoUtil;
import org.camunda.tngp.client.AsyncTasksClient;
import org.camunda.tngp.client.TngpClient;
import org.camunda.tngp.client.cmd.BrokerRequestException;
import org.camunda.tngp.client.cmd.LockedTask;
import org.camunda.tngp.client.cmd.LockedTasksBatch;
import org.camunda.tngp.client.task.Payload;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;

/**
 * Tests the entire cycle of task creation, polling and completion as a smoke test for when something gets broken
 *
 * @author Lindhauer
 */
public class TaskQueueTest
{
    public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();

    public ClientRule clientRule = new ClientRule();

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(brokerRule)
        .around(clientRule);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void testCycle()
    {
        final TngpClient client = clientRule.getClient();
        final AsyncTasksClient taskService = client.tasks();

        System.out.println("Creating task");

        final Long taskId = taskService.create()
            .taskQueueId(0)
            .taskType("bar")
            .execute();

        assertThat(taskId).isGreaterThanOrEqualTo(0);

        System.out.println("Locking task");

        final LockedTasksBatch lockedTasksBatch = taskService.pollAndLock()
            .taskQueueId(0)
            .taskType("bar")
            .lockTime(100 * 1000)
            .execute();

        assertThat(lockedTasksBatch.getLockedTasks()).hasSize(1);

        final LockedTask task = lockedTasksBatch.getLockedTasks().get(0);
        assertThat(task.getId()).isEqualTo(taskId);

        System.out.println("Completing task");

        final Long completedTaskId = taskService.complete()
            .taskQueueId(0)
            .taskId(taskId)
            .execute();

        assertThat(completedTaskId).isEqualTo(taskId);
    }

    @Test
    public void testCannotCompleteUnlockedTask()
    {
        final TngpClient client = clientRule.getClient();
        final AsyncTasksClient taskService = client.tasks();

        final Long taskId = taskService.create()
            .taskQueueId(0)
            .taskType("bar")
            .execute();

        assertThat(taskId).isGreaterThanOrEqualTo(0);

        exception.expect(BrokerRequestException.class);
        exception.expectMessage("Task does not exist or is not locked");

        taskService.complete()
            .taskQueueId(0)
            .taskId(taskId)
            .execute();
    }

    @Test
    public void testCannotCompleteTaskTwiceInParallel()
    {
        // given
        final TngpClient client = clientRule.getClient();
        final AsyncTasksClient taskClient = client.tasks();

        final Long taskId = taskClient.create()
            .taskQueueId(0)
            .taskType("bar")
            .execute();

        taskClient.pollAndLock()
            .taskQueueId(0)
            .taskType("bar")
            .lockTime(Duration.ofHours(1L))
            .execute();


        final ParallelRequests parallelRequests = ParallelRequests.prepare();

        final SilentFuture<Long> future1 = parallelRequests.submitRequest(
            () -> taskClient.complete()
                .taskQueueId(0)
                .taskId(taskId)
                .execute());

        final SilentFuture<Long> future2 = parallelRequests.submitRequest(
            () -> taskClient.complete()
                .taskQueueId(0)
                .taskId(taskId)
                .execute());

        // when
        parallelRequests.execute();

        // then
        final Set<Long> results = new HashSet<>();
        results.add(future1.get());
        results.add(future2.get());

        assertThat(results).contains(taskId, null);
    }

    @Test
    public void testLockZeroTasks()
    {
        // given
        final AsyncTasksClient taskService = clientRule.getClient().tasks();

        // when
        final LockedTasksBatch lockedTasksBatch = taskService.pollAndLock()
                .taskQueueId(0)
                .taskType("bar")
                .lockTime(100 * 1000)
                .execute();

        // when
        assertThat(lockedTasksBatch.getLockedTasks()).isEmpty();
    }

    @Test
    public void testLockTaskWithPayload() throws IOException
    {
        // given
        final TngpClient client = clientRule.getClient();
        final AsyncTasksClient taskService = client.tasks();

        System.out.println("Creating task");

        final byte[] payloadBytes = "{\"foo\":\"bar\"}".getBytes(StandardCharsets.UTF_8);
        final Long taskId = taskService.create()
            .taskQueueId(0)
            .payload(payloadBytes)
            .taskType("bar")
            .execute();

        // when
        final LockedTasksBatch lockedTasksBatch = taskService.pollAndLock()
            .taskQueueId(0)
            .taskType("bar")
            .lockTime(10000L)
            .execute();

        // then
        assertThat(lockedTasksBatch).isNotNull();

        final List<LockedTask> tasks = lockedTasksBatch.getLockedTasks();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getId()).isEqualTo(taskId);
        final Payload payload = tasks.get(0).getPayload();
        System.out.println(IoUtil.getStringFromInputStream(payload.getRaw()));
        assertThat(payload.getRaw()).hasSameContentAs(new ByteArrayInputStream(payloadBytes));

    }

}
