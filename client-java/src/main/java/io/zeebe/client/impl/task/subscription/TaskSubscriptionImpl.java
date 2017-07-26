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
package io.zeebe.client.impl.task.subscription;

import org.slf4j.Logger;
import io.zeebe.client.cmd.TaskCommand;
import io.zeebe.client.impl.Loggers;
import io.zeebe.client.impl.TaskTopicClientImpl;
import io.zeebe.client.impl.data.MsgPackMapper;
import io.zeebe.client.task.PollableTaskSubscription;
import io.zeebe.client.task.TaskHandler;
import io.zeebe.client.task.TaskSubscription;

public class TaskSubscriptionImpl
    extends EventSubscription<TaskSubscriptionImpl>
    implements TaskSubscription, PollableTaskSubscription
{
    protected static final Logger LOGGER = Loggers.TASK_SUBSCRIPTION_LOGGER;

    protected final TaskHandler taskHandler;
    protected final TaskTopicClientImpl taskClient;

    protected final String taskType;
    protected final long lockTime;
    protected final String lockOwner;

    protected boolean autoComplete;
    protected MsgPackMapper msgPackMapper;

    public TaskSubscriptionImpl(
            TaskTopicClientImpl client,
            TaskHandler taskHandler,
            String taskType,
            long lockTime,
            String lockOwner,
            int capacity,
            MsgPackMapper msgPackMapper,
            boolean autoComplete,
            EventAcquisition<TaskSubscriptionImpl> acqusition)
    {
        super(capacity, acqusition);
        this.taskClient = client;
        this.taskHandler = taskHandler;
        this.taskType = taskType;
        this.lockTime = lockTime;
        this.lockOwner = lockOwner;
        this.autoComplete = autoComplete;
        this.msgPackMapper = msgPackMapper;
    }

    public String getTaskType()
    {
        return taskType;
    }

    public long getLockTime()
    {
        return lockTime;
    }

    public String getLockOwner()
    {
        return lockOwner;
    }


    @Override
    public int poll()
    {
        return poll(taskHandler);
    }

    @Override
    public int poll(TaskHandler taskHandler)
    {
        int polledEvents = pollEvents((e) ->
        {
            final TaskCommand taskEvent = msgPackMapper.convert(e.getAsMsgPack(), TaskCommand.class);
            final TaskImpl task = new TaskImpl(taskClient, this, e.getEventKey(), taskEvent);

            try
            {
                taskHandler.handle(task);

                if (autoComplete && !task.isCompleted())
                {
                    task.complete();
                }
            }
            catch (Exception ex)
            {
                LOGGER.info("An error ocurred when handling task " + task.getKey() + ". Reporting to broker.", ex);
                task.fail(ex);
            }
        });

        return polledEvents;
    }

    @Override
    public boolean isManagedSubscription()
    {
        return taskHandler != null;
    }

    @Override
    protected void requestEventSourceReplenishment(int eventsProcessed)
    {
        taskClient.increaseSubscriptionCredits()
            .subscriptionId(subscriberKey)
            .credits(eventsProcessed)
            .execute();
    }

    @Override
    public EventSubscriptionCreationResult requestNewSubscription()
    {
        return taskClient.brokerTaskSubscription()
                .taskType(taskType)
                .lockDuration(lockTime)
                .lockOwner(lockOwner)
                .initialCredits(capacity)
                .execute();
    }

    @Override
    public void requestSubscriptionClose()
    {
        taskClient.closeBrokerTaskSubscription()
            .subscriptionId(subscriberKey)
            .execute();

    }

    @Override
    public String toString()
    {
        return "TaskSubscriptionImpl [taskType=" + taskType + ", subscriberKey=" + subscriberKey + "]";
    }

    @Override
    public String getTopicName()
    {
        return taskClient.getTopic().getTopicName();
    }

    @Override
    public int getPartitionId()
    {
        return taskClient.getTopic().getPartitionId();
    }
}
