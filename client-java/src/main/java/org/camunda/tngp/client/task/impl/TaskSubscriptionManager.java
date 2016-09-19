package org.camunda.tngp.client.task.impl;

import java.util.concurrent.TimeUnit;

import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.camunda.tngp.client.impl.TngpClientImpl;
import org.camunda.tngp.client.task.PollableTaskSubscriptionBuilder;
import org.camunda.tngp.client.task.TaskSubscriptionBuilder;

public class TaskSubscriptionManager
{

    protected TaskAcquisition acqusition;
    protected AgentRunner acquisitionRunner;
    protected AgentRunner[] executionRunners;

    protected TaskSubscriptions subscriptions;

    public TaskSubscriptionManager(TngpClientImpl client, int numExecutionThreads)
    {
        subscriptions = new TaskSubscriptions();

        acqusition = new TaskAcquisition(client, subscriptions);
        acquisitionRunner = newAgentRunner(acqusition);
        executionRunners = new AgentRunner[numExecutionThreads];
        for (int i = 0; i < numExecutionThreads; i++)
        {
            executionRunners[i] = newAgentRunner(new TaskExecutor(subscriptions));
        }
    }

    public void startAcquisition()
    {
        AgentRunner.startOnThread(acquisitionRunner);
    }

    public void stopAcquisition()
    {
        acquisitionRunner.close();
    }

    public void startExecution()
    {
        for (int i = 0; i < executionRunners.length; i++)
        {
            AgentRunner.startOnThread(executionRunners[i]);
        }
    }

    public void stopExecution()
    {
        for (int i = 0; i < executionRunners.length; i++)
        {
            executionRunners[i].close();
        }
    }

    protected static AgentRunner newAgentRunner(Agent agent)
    {
        return new AgentRunner(
            new BackoffIdleStrategy(1000, 100, 100, TimeUnit.MILLISECONDS.toNanos(10)),
            (e) -> e.printStackTrace(),
            null,
            agent);
    }

    public void closeAllSubscriptions()
    {
        this.subscriptions.closeAll();
    }

    public TaskSubscriptionBuilder newSubscription()
    {
        return new TaskSubscriptionBuilderImpl(acqusition);
    }

    public PollableTaskSubscriptionBuilder newPollableSubscription()
    {
        return new PollableTaskSubscriptionBuilderImpl(acqusition);
    }

}
