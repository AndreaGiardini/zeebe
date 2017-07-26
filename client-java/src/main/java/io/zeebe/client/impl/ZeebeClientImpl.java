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
package io.zeebe.client.impl;

import static io.zeebe.client.ClientProperties.CLIENT_MAXREQUESTS;
import static io.zeebe.client.ClientProperties.CLIENT_SENDBUFFER_SIZE;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ClientProperties;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.cmd.Command;
import io.zeebe.client.event.Event;
import io.zeebe.client.impl.cmd.ClientCommandManager;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.impl.task.subscription.SubscriptionManager;
import io.zeebe.client.impl.topology.ClientTopologyManager;
import io.zeebe.client.topology.Topology;
import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.dispatcher.Dispatchers;
import io.zeebe.transport.*;
import io.zeebe.util.actor.*;
import org.msgpack.jackson.dataformat.MessagePackFactory;

public class ZeebeClientImpl implements ZeebeClient
{
    protected final Properties initializationProperties;

    protected SocketAddress contactPoint;
    protected Dispatcher dataFrameReceiveBuffer;
    protected Dispatcher sendBuffer;
    protected ActorScheduler transportActorScheduler;

    protected ClientTransport transport;

    protected final ObjectMapper objectMapper;

    protected SubscriptionManager subscriptionManager;

    protected final ClientTopologyManager topologyManager;
    protected final ClientCommandManager apiCommandManager;

    protected ActorReference topologyManagerActorReference;
    protected ActorReference commandManagerActorReference;

    protected boolean connected = false;

    protected final MsgPackConverter msgPackConverter;

    public ZeebeClientImpl(final Properties properties)
    {
        ClientProperties.setDefaults(properties);
        this.initializationProperties = properties;

        contactPoint = SocketAddress.from(properties.getProperty(ClientProperties.BROKER_CONTACTPOINT));

        final int maxRequests = Integer.parseInt(properties.getProperty(CLIENT_MAXREQUESTS));
        final int sendBufferSize = Integer.parseInt(properties.getProperty(CLIENT_SENDBUFFER_SIZE));

        this.transportActorScheduler = ActorSchedulerBuilder.createDefaultScheduler("transport");

        dataFrameReceiveBuffer = Dispatchers.create("receive-buffer")
            .bufferSize(1024 * 1024 * sendBufferSize)
            .modePubSub()
            .frameMaxLength(1024 * 1024)
            .actorScheduler(transportActorScheduler)
            .build();
        sendBuffer = Dispatchers.create("send-buffer")
            .actorScheduler(transportActorScheduler)
            .bufferSize(1024 * 1024 * sendBufferSize)
            .subscriptions("sender")
//                .countersManager(countersManager) // TODO: counters manager
            .build();

        transport = Transports.newClientTransport()
                .messageMaxLength(1024 * 1024)
                .messageReceiveBuffer(dataFrameReceiveBuffer)
                .requestPoolSize(maxRequests + 16)
                .scheduler(transportActorScheduler)
                .sendBuffer(sendBuffer)
                .build();

        // TODO: configure keep-alive
//        final long keepAlivePeriod = Long.parseLong(properties.getProperty(CLIENT_TCP_CHANNEL_KEEP_ALIVE_PERIOD));

        objectMapper = new ObjectMapper(new MessagePackFactory());
        objectMapper.setSerializationInclusion(Include.NON_NULL);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        msgPackConverter = new MsgPackConverter();

        final int numExecutionThreads = Integer.parseInt(properties.getProperty(ClientProperties.CLIENT_TASK_EXECUTION_THREADS));
        final Boolean autoCompleteTasks = Boolean.parseBoolean(properties.getProperty(ClientProperties.CLIENT_TASK_EXECUTION_AUTOCOMPLETE));

        final int prefetchCapacity = Integer.parseInt(properties.getProperty(ClientProperties.CLIENT_TOPIC_SUBSCRIPTION_PREFETCH_CAPACITY));
        subscriptionManager = new SubscriptionManager(
                this,
                numExecutionThreads,
                autoCompleteTasks,
                prefetchCapacity,
                dataFrameReceiveBuffer.openSubscription("task-acquisition"));
        transport.registerChannelListener(subscriptionManager);

        topologyManager = new ClientTopologyManager(transport, objectMapper, contactPoint);
        apiCommandManager = new ClientCommandManager(transport, topologyManager, maxRequests);

        commandManagerActorReference = transportActorScheduler.schedule(apiCommandManager);
        topologyManagerActorReference = transportActorScheduler.schedule(topologyManager);

        subscriptionManager.start();
    }

    @Override
    public void disconnectAll()
    {
        subscriptionManager.closeAllSubscriptions();
        transport.closeAllChannels().join();
    }

    @Override
    public void close()
    {
        subscriptionManager.stop();
        subscriptionManager.close();

        topologyManagerActorReference.close();
        topologyManagerActorReference = null;

        commandManagerActorReference.close();
        commandManagerActorReference = null;

        try
        {
            transport.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            dataFrameReceiveBuffer.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            sendBuffer.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }

        transportActorScheduler.close();
    }

    @Override
    public Topology getTopology()
    {
        topologyManager.refreshNow()
            .join();

        return topologyManager.getTopology();
    }

    public ClientCommandManager getCommandManager()
    {
        return apiCommandManager;
    }

    public ClientTopologyManager getTopologyManager()
    {
        return topologyManager;
    }

    public SubscriptionManager getSubscriptionManager()
    {
        return subscriptionManager;
    }

    public ObjectMapper getObjectMapper()
    {
        return objectMapper;
    }

    public Properties getInitializationProperties()
    {
        return initializationProperties;
    }

    public ClientTransport getTransport()
    {
        return transport;
    }

    public MsgPackConverter getMsgPackConverter()
    {
        return msgPackConverter;
    }

    @SuppressWarnings("unchecked")
    public <E extends Event> E execute(E event, String expectedState)
    {
        return (E) apiCommandManager.execute(event, expectedState);
    }

    @SuppressWarnings("unchecked")
    public <E extends Event> CompletableFuture<E> executeAsync(E event, String expectedState)
    {
        return apiCommandManager.executeAsync(event, expectedState);
    }
}
