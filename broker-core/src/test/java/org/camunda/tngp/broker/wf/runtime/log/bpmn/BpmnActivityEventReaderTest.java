package org.camunda.tngp.broker.wf.runtime.log.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.tngp.broker.test.util.BufferAssert.assertThatBuffer;

import java.nio.charset.StandardCharsets;

import org.camunda.tngp.graph.bpmn.ExecutionEventType;
import org.camunda.tngp.protocol.log.BpmnActivityEventEncoder;
import org.camunda.tngp.protocol.log.MessageHeaderEncoder;
import org.junit.Before;
import org.junit.Test;

import org.agrona.concurrent.UnsafeBuffer;

public class BpmnActivityEventReaderTest
{

    protected UnsafeBuffer eventBuffer = new UnsafeBuffer(new byte[1024 * 1024]);
    protected int eventLength;

    @Before
    public void writeEventToBuffer()
    {
        final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        final BpmnActivityEventEncoder bodyEncoder = new BpmnActivityEventEncoder();

        headerEncoder.wrap(eventBuffer, 0)
            .blockLength(bodyEncoder.sbeBlockLength())
            .resourceId(1)
            .schemaId(bodyEncoder.sbeSchemaId())
            .shardId(2)
            .templateId(bodyEncoder.sbeTemplateId())
            .version(bodyEncoder.sbeSchemaVersion());

        bodyEncoder.wrap(eventBuffer, headerEncoder.encodedLength())
            .event(ExecutionEventType.EVT_OCCURRED.value())
            .flowElementId(75)
            .key(1234L)
            .wfDefinitionId(8765L)
            .wfInstanceId(45678L)
            .taskQueueId(5)
            .taskType("ping")
            .flowElementIdString("pong");

        eventLength = headerEncoder.encodedLength() + bodyEncoder.encodedLength();
    }

    @Test
    public void shouldReadEvent()
    {
        // given
        final BpmnActivityEventReader reader = new BpmnActivityEventReader();

        // when
        reader.wrap(eventBuffer, 0, eventLength);

        // then
        assertThat(reader.event()).isEqualTo(ExecutionEventType.EVT_OCCURRED);
        assertThat(reader.flowElementId()).isEqualTo(75);
        assertThat(reader.key()).isEqualTo(1234L);
        assertThat(reader.wfDefinitionId()).isEqualTo(8765L);
        assertThat(reader.wfInstanceId()).isEqualTo(45678L);
        assertThat(reader.taskQueueId()).isEqualTo(5);
        assertThatBuffer(reader.getTaskType())
            .hasCapacity(4)
            .hasBytes("ping".getBytes(StandardCharsets.UTF_8));
        assertThatBuffer(reader.getFlowElementIdString())
            .hasCapacity(4)
            .hasBytes("pong".getBytes(StandardCharsets.UTF_8));
    }
}
