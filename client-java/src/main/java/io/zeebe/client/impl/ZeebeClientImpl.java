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

import org.msgpack.jackson.dataformat.MessagePackFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.zeebe.client.ClientProperties;
import io.zeebe.client.WorkflowsClient;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.clustering.impl.ClientTopologyManager;
import io.zeebe.client.clustering.impl.RequestTopologyCmdImpl;
import io.zeebe.client.clustering.impl.TopologyResponse;
import io.zeebe.client.event.impl.TopicClientImpl;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.task.cmd.Request;
import io.zeebe.client.task.impl.subscription.SubscriptionManager;
import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.dispatcher.Dispatchers;
import io.zeebe.transport.ClientTransport;
import io.zeebe.transport.ClientTransportBuilder;
import io.zeebe.transport.SocketAddress;
import io.zeebe.transport.Transports;
import io.zeebe.util.actor.ActorReference;
import io.zeebe.util.actor.ActorScheduler;
import io.zeebe.util.actor.ActorSchedulerBuilder;

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

        final ClientTransportBuilder transportBuilder = Transports.newClientTransport()
            .messageMaxLength(1024 * 1024)
            .messageReceiveBuffer(dataFrameReceiveBuffer)
            .requestPoolSize(maxRequests + 16)
            .scheduler(transportActorScheduler)
            .sendBuffer(sendBuffer);

        if (properties.containsKey(ClientProperties.CLIENT_TCP_CHANNEL_KEEP_ALIVE_PERIOD))
        {
            final long keepAlivePeriod = Long.parseLong(properties.getProperty(ClientProperties.CLIENT_TCP_CHANNEL_KEEP_ALIVE_PERIOD));
            transportBuilder.keepAlivePeriod(keepAlivePeriod);
        }

        transport = transportBuilder.build();

        final MessagePackFactory messagePackFactory = new MessagePackFactory()
                .setReuseResourceInGenerator(false)
                .setReuseResourceInParser(false);
        objectMapper = new ObjectMapper(messagePackFactory);
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
        apiCommandManager = new ClientCommandManager(transport, topologyManager, objectMapper, maxRequests);
    }

    @Override
    public void connect()
    {
        if (!connected)
        {
            commandManagerActorReference = transportActorScheduler.schedule(apiCommandManager);
            topologyManagerActorReference = transportActorScheduler.schedule(topologyManager);

            subscriptionManager.start();

            connected = true;
        }
    }

    @Override
    public void disconnect()
    {
        if (connected)
        {
            subscriptionManager.closeAllSubscriptions();
            subscriptionManager.stop();

            topologyManagerActorReference.close();
            topologyManagerActorReference = null;

            commandManagerActorReference.close();
            commandManagerActorReference = null;

            transport.closeAllChannels().join();

            connected = false;
        }
    }

    @Override
    public void close()
    {
        disconnect();

        subscriptionManager.close();

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
    public Request<TopologyResponse> requestTopology()
    {
        return new RequestTopologyCmdImpl(apiCommandManager);
    }

    @Override
    public TasksClientImpl tasks()
    {
        return new TasksClientImpl(this);
    }

    @Override
    public WorkflowsClient workflows()
    {
        return new WorkflowsClientImpl(this);
    }

    @Override
    public TopicClientImpl topics()
    {
        return new TopicClientImpl(this);
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
}
