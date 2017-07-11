package io.zeebe.client.clustering.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.clustering.Topology;
import io.zeebe.client.cmd.BrokerRequestException;
import io.zeebe.client.impl.Topic;
import io.zeebe.client.impl.cmd.ClientResponseHandler;
import io.zeebe.protocol.clientapi.ErrorResponseDecoder;
import io.zeebe.protocol.clientapi.MessageHeaderDecoder;
import io.zeebe.transport.*;
import io.zeebe.util.DeferredCommandContext;
import io.zeebe.util.actor.Actor;
import io.zeebe.util.buffer.BufferReader;
import org.agrona.DirectBuffer;


public class ClientTopologyManager implements Actor, BufferReader
{
    protected final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    protected final ErrorResponseDecoder errorResponseDecoder = new ErrorResponseDecoder();

    protected final DeferredCommandContext commandContext = new DeferredCommandContext();

    protected final RequestTopologyCmdImpl requestTopologyCmd;
    protected final ClientTopologyController clientTopologyController;
    protected final List<CompletableFuture<Void>> refreshFutures;

    protected TopologyImpl topology;
    protected CompletableFuture<Void> refreshFuture;
    private ClientTransport transport;

    public ClientTopologyManager(final ClientTransport transport, final ObjectMapper objectMapper, final SocketAddress... initialBrokers)
    {
        this.transport = transport;
        this.clientTopologyController = new ClientTopologyController(transport);
        this.requestTopologyCmd = new RequestTopologyCmdImpl(null, objectMapper);
        this.topology = new TopologyImpl();

        for (SocketAddress socketAddress : initialBrokers)
        {
            topology.addBroker(transport.registerRemoteAddress(socketAddress));
        }

        this.refreshFutures = new ArrayList<>();
        triggerRefresh();
    }

    @Override
    public int doWork() throws Exception
    {
        int workCount = 0;

        workCount += commandContext.doWork();
        workCount += clientTopologyController.doWork();

        return workCount;
    }

    public Topology getTopology()
    {
        return topology;
    }

    public RemoteAddress getLeaderForTopic(final Topic topic)
    {
        if (topic != null)
        {
            return topology.getLeaderForTopic(topic);
        }
        else
        {
            return topology.getRandomBroker();
        }
    }

    public CompletableFuture<Void> refreshNow()
    {
        return commandContext.runAsync(future ->
        {
            if (clientTopologyController.isIdle())
            {
                triggerRefresh();
            }

            refreshFutures.add(future);

        });
    }

    protected void triggerRefresh()
    {
        refreshFuture = new CompletableFuture<>();

        refreshFuture.handle((value, throwable) ->
        {
            if (throwable == null)
            {
                refreshFutures.forEach(f -> f.complete(value));
            }
            else
            {
                refreshFutures.forEach(f -> f.completeExceptionally(throwable));
            }

            refreshFutures.clear();

            return null;
        });

        clientTopologyController.configure(topology.getRandomBroker(), requestTopologyCmd.getRequestWriter(), this, refreshFuture);
    }

    @Override
    public void wrap(final DirectBuffer buffer, final int offset, final int length)
    {
        messageHeaderDecoder.wrap(buffer, 0);

        final int schemaId = messageHeaderDecoder.schemaId();
        final int templateId = messageHeaderDecoder.templateId();
        final int blockLength = messageHeaderDecoder.blockLength();
        final int version = messageHeaderDecoder.version();

        final int responseMessageOffset = messageHeaderDecoder.encodedLength();

        final ClientResponseHandler<TopologyResponse> responseHandler = requestTopologyCmd.getResponseHandler();

        if (schemaId == responseHandler.getResponseSchemaId() && templateId == responseHandler.getResponseTemplateId())
        {
            try
            {
                final TopologyResponse topologyDto = responseHandler.readResponse(buffer, responseMessageOffset, blockLength, version);
                final TopologyImpl topology = new TopologyImpl();

                topology.update(topologyDto, transport);

                this.topology = topology;
            }
            catch (final Exception e)
            {
                throw new RuntimeException("Unable to parse topic list from broker response", e);
            }
        }
        else
        {
            errorResponseDecoder.wrap(buffer, offset, blockLength, version);
            throw new BrokerRequestException(errorResponseDecoder.errorCode(), errorResponseDecoder.errorData());
        }

    }

}
