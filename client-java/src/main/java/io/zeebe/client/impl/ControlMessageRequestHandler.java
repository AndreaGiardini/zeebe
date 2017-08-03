package io.zeebe.client.impl;

import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.io.DirectBufferInputStream;
import org.agrona.io.ExpandableDirectBufferOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.zeebe.client.clustering.impl.ClientTopologyManager;
import io.zeebe.client.task.impl.ControlMessageRequest;
import io.zeebe.protocol.clientapi.ControlMessageRequestDecoder;
import io.zeebe.protocol.clientapi.ControlMessageRequestEncoder;
import io.zeebe.protocol.clientapi.ControlMessageResponseDecoder;
import io.zeebe.protocol.clientapi.MessageHeaderDecoder;
import io.zeebe.protocol.clientapi.MessageHeaderEncoder;
import io.zeebe.transport.RemoteAddress;

public class ControlMessageRequestHandler implements RequestResponseHandler
{
    protected final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    protected final ControlMessageRequestEncoder encoder = new ControlMessageRequestEncoder();

    protected final ControlMessageRequestDecoder decoder = new ControlMessageRequestDecoder();

    protected final ObjectMapper objectMapper;

    protected ExpandableArrayBuffer serializedMessage = new ExpandableArrayBuffer();
    protected int serializedMessageLength = 0;

    protected ControlMessageRequest message;

    public ControlMessageRequestHandler(ObjectMapper objectMapper)
    {
        this.objectMapper = objectMapper;
    }

    public void configure(ControlMessageRequest controlMessage)
    {
        this.message = controlMessage;
        serialize(controlMessage);
    }

    protected void serialize(ControlMessageRequest message)
    {
        int offset = 0;
        headerEncoder.wrap(serializedMessage, offset)
            .blockLength(encoder.sbeBlockLength())
            .schemaId(encoder.sbeSchemaId())
            .templateId(encoder.sbeTemplateId())
            .version(encoder.sbeSchemaVersion());

        offset += headerEncoder.encodedLength();

        encoder.wrap(serializedMessage, offset);

        encoder.messageType(message.getType());

        offset = encoder.limit();
        final int serializedMessageOffset = offset + ControlMessageRequestEncoder.dataHeaderLength();

        final ExpandableDirectBufferOutputStream out = new ExpandableDirectBufferOutputStream(serializedMessage, serializedMessageOffset);
        try
        {
            objectMapper.writeValue(out, message.getRequest());
        }
        catch (final Throwable e)
        {
            throw new RuntimeException("Failed to serialize message", e);
        }

        serializedMessage.putShort(offset, (short)out.position(), java.nio.ByteOrder.LITTLE_ENDIAN);

        serializedMessageLength = serializedMessageOffset + out.position();
    }

    @Override
    public int getLength()
    {
        return serializedMessageLength;
    }

    @Override
    public void write(MutableDirectBuffer buffer, int offset)
    {
        buffer.putBytes(offset, serializedMessage, 0, serializedMessageLength);
    }

    @Override
    public boolean handlesResponse(MessageHeaderDecoder responseHeader)
    {
        return responseHeader.schemaId() == ControlMessageResponseDecoder.SCHEMA_ID && responseHeader.templateId() == ControlMessageResponseDecoder.TEMPLATE_ID;
    }

    @SuppressWarnings({ "unchecked", "resource" })
    @Override
    public Object getResult(DirectBuffer buffer, int offset, int blockLength, int version)
    {
        decoder.wrap(buffer, offset, blockLength, version);

        final int dataLength = decoder.dataLength();
        final DirectBufferInputStream inStream = new DirectBufferInputStream(
                buffer,
                decoder.limit() + ControlMessageRequestDecoder.dataHeaderLength(),
                dataLength);

        try
        {
            return objectMapper.readValue(inStream, message.getResponseClass());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Cannot deserialize event in response", e);
        }
    }

    @Override
    public RemoteAddress getTarget(ClientTopologyManager currentTopology)
    {
        return currentTopology.getLeaderForTopic(message.getTarget());
    }

    @Override
    public String describeRequest()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append("[ target = ");
        if (message.getTarget() != null)
        {
            sb.append(message.getTarget());
        }
        else
        {
            sb.append("random broker");
        }
        sb.append(", type = ");
        sb.append(message.getType().name());
        sb.append("]");

        return sb.toString();
    }

}
