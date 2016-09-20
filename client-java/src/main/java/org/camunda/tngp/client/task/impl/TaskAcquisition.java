package org.camunda.tngp.client.task.impl;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.camunda.tngp.client.cmd.LockedTask;
import org.camunda.tngp.client.cmd.LockedTasksBatch;
import org.camunda.tngp.client.impl.TngpClientImpl;

public class TaskAcquisition implements Agent, Consumer<AcquisitionCmd>
{
    public static final String ROLE_NAME = "task-acquisition";

    protected TngpClientImpl client;
    protected TaskSubscriptions taskSubscriptions;
    protected ManyToOneConcurrentArrayQueue<AcquisitionCmd> cmdQueue;

    public TaskAcquisition(TngpClientImpl client, TaskSubscriptions subscriptions)
    {
        this.client = client;
        this.taskSubscriptions = subscriptions;
        this.cmdQueue = new ManyToOneConcurrentArrayQueue<>(32);
    }

    @Override
    public int doWork() throws Exception
    {
        int workCount = 0;

        workCount += evaluateCommands();
        workCount += acquireTasksForSubscriptions();

        return workCount;
    }

    public int evaluateCommands()
    {
        return cmdQueue.drain(this);
    }

    public int acquireTasksForSubscriptions()
    {
        int workCount = 0;

        workCount += workOnSubscriptions(taskSubscriptions.getManagedExecutionSubscriptions());
        workCount += workOnSubscriptions(taskSubscriptions.getPollableSubscriptions());

        return workCount;
    }

    protected int workOnSubscriptions(Collection<TaskSubscriptionImpl> subscriptions)
    {
        int workCount = 0;
        for (TaskSubscriptionImpl subscription : subscriptions)
        {
            if (subscription.isOpen())
            {
                acquireTasks(subscription);
            }
            else if (subscription.isClosing() && !subscription.hasQueuedTasks())
            {
                subscription.doClose();
                taskSubscriptions.removeSubscription(subscription);
                workCount++;
            }
        }

        return workCount;
    }

    @Override
    public String roleName()
    {
        return ROLE_NAME;
    }

    @Override
    public void accept(AcquisitionCmd t)
    {
        t.execute(this);
    }

    public void openSubscription(TaskSubscriptionImpl subscription)
    {
        if (subscription.isManagedSubscription())
        {
            taskSubscriptions.addManagedExecutionSubscription(subscription);
        }
        else
        {
            taskSubscriptions.addPollableSubscription(subscription);
        }

        subscription.doOpen();
    }

    protected void acquireTasks(TaskSubscriptionImpl subscription)
    {
        final LockedTasksBatch tasksBatch = client.pollAndLock()
                .taskQueueId(subscription.getTaskQueueId())
                .lockTime(subscription.getLockTime())
                .maxTasks(subscription.getMaxTasks())
                .taskType(subscription.getTaskType())
                .execute();


        final List<LockedTask> lockedTasks = tasksBatch.getLockedTasks();

        for (int i = 0; i < lockedTasks.size(); i++)
        {
            final LockedTask lockedTask = lockedTasks.get(i);

            final TaskImpl task = new TaskImpl(
                    client,
                    lockedTask.getId(),
                    lockedTask.getWorkflowInstanceId(),
                    subscription.getTaskType(),
                    new Date(0L),
                    subscription.getTaskQueueId());

            subscription.addTask(task); // cannot fail when there is a single thread that adds to the queue
        }
    }

    public void scheduleCommand(AcquisitionCmd command)
    {
        cmdQueue.offer(command);
        // TODO: do something if command does not fit in queue
    }

}
