package io.zeebe.broker.it.task;

import static io.zeebe.broker.it.util.TopicEventRecorder.taskEvent;
import static io.zeebe.broker.it.util.TopicEventRecorder.taskRetries;
import static io.zeebe.test.util.TestUtil.doRepeatedly;
import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import io.zeebe.broker.it.ClientRule;
import io.zeebe.broker.it.EmbeddedBrokerRule;
import io.zeebe.broker.it.util.RecordingTaskHandler;
import io.zeebe.broker.it.util.TopicEventRecorder;
import io.zeebe.client.ClientProperties;
import io.zeebe.client.TaskTopicClient;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.event.TaskEvent;
import io.zeebe.client.task.PollableTaskSubscription;
import io.zeebe.client.task.Task;
import io.zeebe.client.task.TaskSubscription;
import io.zeebe.test.util.TestUtil;
import io.zeebe.util.time.ClockUtil;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;

public class TaskSubscriptionTest
{
    public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();

    public ClientRule clientRule = new ClientRule(() ->
    {
        final Properties properties = new Properties();

        properties.put(ClientProperties.CLIENT_TASK_EXECUTION_AUTOCOMPLETE, false);

        return properties;
    });

    public TopicEventRecorder eventRecorder = new TopicEventRecorder(clientRule, false);

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(brokerRule)
        .around(clientRule)
        .around(eventRecorder);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Rule
    public Timeout timeout = Timeout.seconds(20 * 1000);

    @After
    public void cleanUp()
    {
        ClockUtil.reset();
    }

    @Test
    public void shouldOpenSubscription() throws InterruptedException
    {
        // given
        final TaskTopicClient topicClient = clientRule.taskTopic();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .execute();

        // when
        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        // then
        waitUntil(() -> !taskHandler.getHandledTasks().isEmpty());

        assertThat(taskHandler.getHandledTasks()).hasSize(1);
        assertThat(taskHandler.getHandledTasks().get(0).getKey()).isEqualTo(taskKey);
    }

    @Test
    public void shouldCompleteTask() throws InterruptedException
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .payload("{ \"a\" : 1 }")
            .addHeader("b", "2")
            .execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        waitUntil(() -> !taskHandler.getHandledTasks().isEmpty());

        // when
        final Long result = topicClient.complete()
            .taskKey(taskKey)
            .lockOwner("test")
            .taskType("foo")
            .payload("{ \"a\" : 2 }")
            .addHeader("b", "3")
            .addHeader("c", "4")
            .execute();

        // then
        assertThat(result).isEqualTo(taskKey);
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));

        TaskEvent taskEvent = eventRecorder.getTaskEvents(taskEvent("CREATE")).get(0).getEvent();
        assertThat(taskEvent.getLockExpirationTime()).isNull();
        assertThat(taskEvent.getLockOwner()).isNull();

        taskEvent = eventRecorder.getTaskEvents(taskEvent("CREATED")).get(0).getEvent();
        assertThat(taskEvent.getLockExpirationTime()).isNull();

        taskEvent = eventRecorder.getTaskEvents(taskEvent("LOCKED")).get(0).getEvent();
        assertThat(taskEvent.getLockExpirationTime()).isNotNull();
        assertThat(taskEvent.getLockOwner()).isEqualTo("test");
    }

    @Test
    public void shouldCompletionTaskInHandler() throws InterruptedException
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .payload("{\"a\":1}")
            .addHeader("b", "2")
            .execute();

        // when
        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(task ->
        {
            task.complete("{\"a\":3}");
        });

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        // then
        waitUntil(() -> !taskHandler.getHandledTasks().isEmpty());

        assertThat(taskHandler.getHandledTasks()).hasSize(1);

        final Task task = taskHandler.getHandledTasks().get(0);
        assertThat(task.getKey()).isEqualTo(taskKey);
        assertThat(task.getType()).isEqualTo("foo");
        assertThat(task.getLockExpirationTime()).isAfter(Instant.now());

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));

        final TaskEvent taskEvent = eventRecorder.getTaskEvents(taskEvent("COMPLETED")).get(0).getEvent();
        assertThat(taskEvent.getPayload()).isEqualTo("{\"a\":3}");
        assertThat(task.getHeaders()).containsEntry("b", "2");
    }

    @Test
    public void shouldCloseSubscription() throws InterruptedException
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();

        final TaskSubscription subscription = topicClient.newTaskSubscription()
                .handler(taskHandler)
                .taskType("foo")
                .lockTime(Duration.ofMinutes(5))
                .lockOwner("test")
                .open();

        // when
        subscription.close();

        // then
        topicClient.create()
            .taskType("foo")
            .execute();

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("CREATED")));

        assertThat(taskHandler.getHandledTasks()).isEmpty();
        assertThat(eventRecorder.hasTaskEvent(taskEvent("LOCK"))).isFalse();
    }

    @Test
    public void shouldFetchAndHandleTasks()
    {
        // given
        final int numTasks = 50;
        for (int i = 0; i < numTasks; i++)
        {
            clientRule.taskTopic().create()
                .taskType("foo")
                .execute();
        }

        final RecordingTaskHandler handler = new RecordingTaskHandler(Task::complete);

        clientRule.taskTopic().newTaskSubscription()
            .handler(handler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .taskFetchSize(10)
            .open();

        // when
        waitUntil(() -> handler.getHandledTasks().size() == numTasks);

        // then
        assertThat(handler.getHandledTasks()).hasSize(numTasks);
    }

    @Test
    public void shouldMarkTaskAsFailedAndRetryIfHandlerThrowsException()
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(
            t ->
            {
                throw new RuntimeException("expected failure");
            },
            Task::complete
            );

        // when
        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        // then the subscription is not broken and other tasks are still handled
        waitUntil(() -> taskHandler.getHandledTasks().size() == 2);

        assertThat(taskHandler.getHandledTasks()).extracting("key").contains(taskKey, taskKey);
        assertThat(eventRecorder.hasTaskEvent(taskEvent("FAILED"))).isTrue();
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
    }

    @Test
    public void shouldNotLockTaskIfRetriesAreExhausted()
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        topicClient.create()
            .taskType("foo")
            .retries(1)
            .execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(
            t ->
            {
                throw new RuntimeException("expected failure");
            });

        // when
        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("FAILED").and(taskRetries(0))));

        assertThat(taskHandler.getHandledTasks()).hasSize(1);
    }

    @Test
    public void shouldUpdateTaskRetries()
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .retries(1)
            .execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(
            t ->
            {
                throw new RuntimeException("expected failure");
            },
            Task::complete);

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        waitUntil(() -> taskHandler.getHandledTasks().size() == 1);
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("FAILED").and(taskRetries(0))));

        // when
        final Long result = topicClient.updateRetries()
            .taskKey(taskKey)
            .taskType("foo")
            .retries(2)
            .execute();

        // then
        assertThat(result).isEqualTo(taskKey);

        waitUntil(() -> taskHandler.getHandledTasks().size() == 2);
        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
    }

    @Test
    public void shouldExpireTaskLock() throws InterruptedException
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(task ->
        {
            // don't complete the task - just wait for lock expiration
        });

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        waitUntil(() -> taskHandler.getHandledTasks().size() == 1);

        // when
        ClockUtil.setCurrentTime(Instant.now().plus(Duration.ofMinutes(5)));

        // then
        waitUntil(() -> taskHandler.getHandledTasks().size() == 2);

        assertThat(taskHandler.getHandledTasks())
            .hasSize(2)
            .extracting("key").contains(taskKey, taskKey);

        assertThat(eventRecorder.hasTaskEvent(taskEvent("LOCK_EXPIRED"))).isTrue();
    }

    @Test
    public void shouldOpenSubscriptionAfterClientReconnect()
    {
        // given
        final ZeebeClient client = clientRule.getClient();

        clientRule.taskTopic().create()
            .taskType("foo")
            .execute();

        // when
        client.disconnect();
        client.connect();

        // then
        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();

        clientRule.taskTopic().newTaskSubscription()
                .taskType("foo")
                .lockTime(Duration.ofMinutes(5))
                .lockOwner("test")
                .handler(taskHandler)
                .open();

        waitUntil(() -> !taskHandler.getHandledTasks().isEmpty());

        assertThat(taskHandler.getHandledTasks()).hasSize(1);
    }

    @Test
    public void shouldGiveTaskToSingleSubscription()
    {
        // given
        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(Task::complete);

        clientRule.taskTopic().newTaskSubscription()
            .taskType("foo")
            .lockTime(Duration.ofHours(1))
            .lockOwner("test")
            .handler(taskHandler)
            .open();

        clientRule.taskTopic().newTaskSubscription()
            .taskType("foo")
            .lockTime(Duration.ofHours(2))
            .lockOwner("test")
            .handler(taskHandler)
            .open();

        // when
        clientRule.taskTopic()
            .create()
            .taskType("foo")
            .execute();

        waitUntil(() -> taskHandler.getHandledTasks().size() == 1);

        // then
        assertThat(taskHandler.getHandledTasks()).hasSize(1);
    }

    @Test
    public void shouldPollTasks()
    {
        // given
        eventRecorder.startRecordingEvents();

        final TaskTopicClient topicClient = clientRule.taskTopic();

        final PollableTaskSubscription subscription = topicClient.newPollableTaskSubscription()
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        final Long taskKey = topicClient.create()
            .taskType("foo")
            .payload("{ \"a\" : 1 }")
            .addHeader("b", "2")
            .execute();

        // when
        final RecordingTaskHandler taskHandler = new RecordingTaskHandler(Task::complete);

        doRepeatedly(() -> subscription.poll(taskHandler))
            .until((workCount) -> workCount == 1);

        assertThat(taskHandler.getHandledTasks()).hasSize(1);

        final Task task = taskHandler.getHandledTasks().get(0);
        assertThat(task.getKey()).isEqualTo(taskKey);
        assertThat(task.getType()).isEqualTo("foo");
        assertThat(task.getLockExpirationTime()).isAfter(Instant.now());
        assertThat(task.getPayload()).isEqualTo("{\"a\":1}");
        assertThat(task.getHeaders()).containsEntry("b", "2");

        waitUntil(() -> eventRecorder.hasTaskEvent(taskEvent("COMPLETED")));
    }

    @Test
    public void shouldSubscribeToMultipleTypes() throws InterruptedException
    {
        // given
        final TaskTopicClient topicClient = clientRule.taskTopic();

        topicClient.create()
            .taskType("foo")
            .execute();

        topicClient.create()
            .taskType("bar")
            .execute();

        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        topicClient.newTaskSubscription()
            .handler(taskHandler)
            .taskType("bar")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        waitUntil(() -> taskHandler.getHandledTasks().size() == 2);
    }

    @Test
    public void shouldHandleMoreTasksThanPrefetchCapacity()
    {
        // given
        final int subscriptionCapacity = 16;

        for (int i = 0; i < subscriptionCapacity + 1; i++)
        {
            clientRule.taskTopic().create()
                .addHeader("key", "value")
                .payload("{}")
                .taskType("foo")
                .execute();
        }
        final RecordingTaskHandler taskHandler = new RecordingTaskHandler();

        // when
        clientRule.taskTopic().newTaskSubscription()
            .handler(taskHandler)
            .taskType("foo")
            .lockTime(Duration.ofMinutes(5))
            .lockOwner("test")
            .open();

        // then
        TestUtil.waitUntil(() -> taskHandler.getHandledTasks().size() > subscriptionCapacity);
    }

}
