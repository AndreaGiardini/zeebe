package io.zeebe.client.impl;

import java.util.concurrent.*;

import io.zeebe.client.clustering.impl.ClientTopologyManager;
import io.zeebe.client.impl.cmd.AbstractCmdImpl;
import io.zeebe.transport.ClientTransport;
import io.zeebe.util.actor.Actor;
import org.agrona.LangUtil;


public class ClientCommandManager implements Actor
{
    private int capacity;

    protected final ClientCommandController[] commandControllers;
    protected final ArrayBlockingQueue<ClientCommandController> pooledCmds;

    protected final ClientTransport transport;
    protected final ClientTopologyManager topologyManager;

    public ClientCommandManager(final ClientTransport transport, final ClientTopologyManager topologyManager, int capacity)
    {
        this.transport = transport;
        this.topologyManager = topologyManager;
        this.capacity = capacity;

        this.pooledCmds = new ArrayBlockingQueue<>(capacity);
        this.commandControllers = new ClientCommandController[capacity];

        for (int i = 0; i < capacity; i++)
        {
            final ClientCommandController controller = new ClientCommandController(transport, topologyManager, ctrl -> pooledCmds.add(ctrl));
            this.commandControllers[i] = controller;
            this.pooledCmds.add(controller);
        }
    }

    @Override
    public int doWork() throws Exception
    {
        int wc = 0;

        for (int i = 0; i < capacity; i++)
        {
            final ClientCommandController controller = commandControllers[i];
            wc += controller.doWork();
        }

        return wc;
    }

    public <R> CompletableFuture<R> executeAsync(final AbstractCmdImpl<R> command)
    {
        final CompletableFuture<R> future = new CompletableFuture<>();

        try
        {
            final ClientCommandController ctrl = pooledCmds.take();
            ctrl.configure(command, future);
        }
        catch (InterruptedException e)
        {
            LangUtil.rethrowUnchecked(e);
        }

        return future;
    }

    public <R> R execute(final AbstractCmdImpl<R> command)
    {
        try
        {
            return executeAsync(command).get();
        }
        catch (final InterruptedException e)
        {
            throw new RuntimeException("Interrupted while executing command");
        }
        catch (final ExecutionException e)
        {
            throw (RuntimeException) e.getCause();
        }
    }
}
