package org.camunda.tngp.client.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.camunda.tngp.broker.test.util.FluentMock;
import org.camunda.tngp.client.cmd.LockedTask;
import org.camunda.tngp.client.cmd.LockedTasksBatch;
import org.camunda.tngp.client.cmd.PollAndLockAsyncTasksCmd;
import org.camunda.tngp.client.impl.TngpClientImpl;
import org.camunda.tngp.client.task.impl.TaskAcquisition;
import org.camunda.tngp.client.task.impl.TaskSubscriptionImpl;
import org.camunda.tngp.client.task.impl.TaskSubscriptions;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TaskSubscriptionTest
{
    @Mock
    protected TngpClientImpl client;

    @FluentMock
    protected PollAndLockAsyncTasksCmd cmd;

    @Mock
    protected TaskHandler taskHandler;

    @Mock
    protected LockedTasksBatch taskBatch;

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);
        when(client.pollAndLock()).thenReturn(cmd);
    }

    @Test
    public void shouldOpenManagedExecutionSubscription() throws Exception
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl(taskHandler, "foo", 0, 123L, 5, acquisition);

        // when
        subscription.openAsync();
        final int workCount = acquisition.evaluateCommands();

        // then
        assertThat(workCount).isEqualTo(1);

        assertThat(subscription.isOpen()).isTrue();
        assertThat(subscriptions.getManagedExecutionSubscriptions()).containsExactly(subscription);
        assertThat(subscriptions.getPollableSubscriptions()).isEmpty();
    }

    @Test
    public void shouldCloseManagedExecutionSubscription()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl(taskHandler, "foo", 0, 123L, 5, acquisition);

        subscription.openAsync();
        acquisition.evaluateCommands();

        // when
        subscription.closeAsnyc();

        // then the subscription is staged as closing
        assertThat(subscription.isOpen()).isFalse();
        assertThat(subscription.isClosing()).isTrue();
        assertThat(subscription.isClosed()).isFalse();
        assertThat(subscriptions.getManagedExecutionSubscriptions()).isNotEmpty();

        // and closed on the next acquisition cycle
        final int workCount = acquisition.acquireTasksForSubscriptions();

        assertThat(workCount).isEqualTo(1);

        assertThat(subscription.isOpen()).isFalse();
        assertThat(subscription.isClosing()).isFalse();
        assertThat(subscription.isClosed()).isTrue();
        assertThat(subscriptions.getManagedExecutionSubscriptions()).isEmpty();
    }

    @Test
    public void shouldInvokeHandlerOnPoll()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl(taskHandler, "foo", 0, 123L, 5, acquisition);

        subscription.openAsync();
        acquisition.evaluateCommands();

        final List<LockedTask> tasks = new ArrayList<>();
        tasks.add(task(1));
        tasks.add(task(2));
        when(taskBatch.getLockedTasks()).thenReturn(tasks);
        when(cmd.execute()).thenReturn(taskBatch);

        acquisition.acquireTasksForSubscriptions();

        // when
        final int workCount = subscription.poll();

        // then
        assertThat(workCount).isEqualTo(2);

        verify(taskHandler).handle(argThat(hasId(1)));
        verify(taskHandler).handle(argThat(hasId(2)));
        verifyNoMoreInteractions(taskHandler);
    }


    @Test
    public void shouldAcquire()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl(taskHandler, "foo", 0, 123L, 5, acquisition);

        subscription.openAsync();
        acquisition.evaluateCommands();

        final List<LockedTask> tasks = Arrays.asList(task(1));
        when(taskBatch.getLockedTasks()).thenReturn(tasks);
        when(cmd.execute()).thenReturn(taskBatch);

        // when
        acquisition.acquireTasksForSubscriptions();

        // then
        verify(client).pollAndLock();
        verify(cmd).lockTime(123L);
        verify(cmd).maxTasks(5);
        verify(cmd).taskQueueId(0);
        verify(cmd).taskType("foo");
        verify(cmd).execute();

        verifyNoMoreInteractions(cmd, client);

        assertThat(subscription.size()).isEqualTo(1);
    }

    @Test
    public void shouldAcquireWithTwoSubscriptionsForSameType()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription1 = new TaskSubscriptionImpl(taskHandler, "foo", 0, 123L, 5, acquisition);
        final TaskSubscriptionImpl subscription2 = new TaskSubscriptionImpl(taskHandler, "foo", 0, 123L, 5, acquisition);

        subscription1.openAsync();
        subscription2.openAsync();
        acquisition.evaluateCommands();

        final List<LockedTask> tasks = Arrays.asList(task(1));
        when(taskBatch.getLockedTasks()).thenReturn(tasks);

        final LockedTasksBatch taskBatch2 = mock(LockedTasksBatch.class);
        when(taskBatch2.getLockedTasks()).thenReturn(Collections.emptyList());

        when(cmd.execute()).thenReturn(taskBatch, taskBatch2);

        // when
        acquisition.acquireTasksForSubscriptions();

        // then
        assertThat(subscription1.size() + subscription2.size()).isEqualTo(1);
    }

    @Test
    public void shouldOpenPollableSubscription()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl("foo", 0, 123L, 5, acquisition);

        // when
        subscription.openAsync();
        acquisition.evaluateCommands();

        // then
        assertThat(subscription.isOpen()).isTrue();
        assertThat(subscriptions.getPollableSubscriptions()).containsExactly(subscription);
        assertThat(subscriptions.getManagedExecutionSubscriptions()).isEmpty();
    }

    @Test
    public void shouldPollSubscription()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl("foo", 0, 123L, 5, acquisition);

        subscription.openAsync();
        acquisition.evaluateCommands();

        final List<LockedTask> tasks = new ArrayList<>();
        tasks.add(task(1));
        tasks.add(task(2));
        when(taskBatch.getLockedTasks()).thenReturn(tasks);
        when(cmd.execute()).thenReturn(taskBatch);

        acquisition.acquireTasksForSubscriptions();

        // when
        int workCount = subscription.poll(taskHandler);

        // then
        assertThat(workCount).isEqualTo(2);

        verify(taskHandler).handle(argThat(hasId(1)));
        verify(taskHandler).handle(argThat(hasId(2)));

        // and polling again does not trigger the handler anymore
        workCount = subscription.poll(taskHandler);
        assertThat(workCount).isEqualTo(0);

        verifyNoMoreInteractions(taskHandler);
    }

    @Test
    public void shouldPopulateTaskProperties()
    {
        // given
        final TaskSubscriptions subscriptions = new TaskSubscriptions();
        final TaskAcquisition acquisition = new TaskAcquisition(client, subscriptions);

        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl("foo", 0, 123L, 5, acquisition);

        subscription.openAsync();
        acquisition.evaluateCommands();

        final List<LockedTask> tasks = new ArrayList<>();
        final LockedTask task = task(1);
        when(task.getWorkflowInstanceId()).thenReturn(444L);
        tasks.add(task);

        when(taskBatch.getLockedTasks()).thenReturn(tasks);
        when(cmd.execute()).thenReturn(taskBatch);

        acquisition.acquireTasksForSubscriptions();

        // when
        subscription.poll(taskHandler);

        // then
        verify(taskHandler).handle(argThat(new ArgumentMatcher<Task>()
        {
            @Override
            public boolean matches(Object argument)
            {
                final Task task = (Task) argument;
                return task.getId() == 1 &&
                        task.getWorkflowInstanceId() == 444L &&
                        "foo".equals(task.getType());
            }
        }));
    }

    protected LockedTask task(long id)
    {
        final LockedTask lockedTask = mock(LockedTask.class);
        when(lockedTask.getId()).thenReturn(id);

        return lockedTask;
    }

    protected static ArgumentMatcher<Task> hasId(final long taskId)
    {
        return new ArgumentMatcher<Task>()
        {
            @Override
            public boolean matches(Object argument)
            {
                return argument instanceof Task && ((Task) argument).getId() == taskId;
            }
        };
    }
}
