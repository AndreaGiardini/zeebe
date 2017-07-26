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
package io.zeebe.client.task.subscription;

import static io.zeebe.test.broker.protocol.clientapi.ClientApiRule.DEFAULT_PARTITION_ID;
import static io.zeebe.test.broker.protocol.clientapi.ClientApiRule.DEFAULT_TOPIC_NAME;
import static io.zeebe.util.VarDataUtil.readBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.impl.Partition;
import io.zeebe.client.impl.cmd.ClientCommandManager;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.impl.task.CloseTaskSubscriptionCmdImpl;
import io.zeebe.client.impl.task.TaskSubscription;
import io.zeebe.protocol.clientapi.*;
import org.agrona.ExpandableArrayBuffer;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.msgpack.jackson.dataformat.MessagePackFactory;

public class CloseTaskSubscriptionCmdTest
{
    private static final byte[] BUFFER = new byte[1014 * 1024];

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final ControlMessageRequestDecoder requestDecoder = new ControlMessageRequestDecoder();

    private final ExpandableArrayBuffer writeBuffer = new ExpandableArrayBuffer();

    private CloseTaskSubscriptionCmdImpl command;
    private ObjectMapper objectMapper;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setup()
    {
        final ClientCommandManager commandManager = mock(ClientCommandManager.class);

        objectMapper = new ObjectMapper(new MessagePackFactory());

        command = new CloseTaskSubscriptionCmdImpl(commandManager, objectMapper, new MsgPackConverter(), new Partition(DEFAULT_TOPIC_NAME, DEFAULT_PARTITION_ID));
    }

    @Test
    public void shouldWriteRequest() throws JsonParseException, JsonMappingException, IOException
    {
        // given
        command
            .subscriptionId(2);

        // when
        command.writeCommand(writeBuffer);

        // then
        headerDecoder.wrap(writeBuffer, 0);

        assertThat(headerDecoder.schemaId()).isEqualTo(requestDecoder.sbeSchemaId());
        assertThat(headerDecoder.version()).isEqualTo(requestDecoder.sbeSchemaVersion());
        assertThat(headerDecoder.templateId()).isEqualTo(requestDecoder.sbeTemplateId());
        assertThat(headerDecoder.blockLength()).isEqualTo(requestDecoder.sbeBlockLength());

        requestDecoder.wrap(writeBuffer, headerDecoder.encodedLength(), requestDecoder.sbeBlockLength(), requestDecoder.sbeSchemaVersion());

        assertThat(requestDecoder.messageType()).isEqualTo(ControlMessageType.REMOVE_TASK_SUBSCRIPTION);

        final byte[] data = readBytes(requestDecoder::getData, requestDecoder::dataLength);

        final TaskSubscription taskSubscription = objectMapper.readValue(data, TaskSubscription.class);

        assertThat(taskSubscription.getSubscriberKey()).isEqualTo(2L);
        assertThat(taskSubscription.getTopicName()).isEqualTo(DEFAULT_TOPIC_NAME);
        assertThat(taskSubscription.getPartitionId()).isEqualTo(DEFAULT_PARTITION_ID);
    }

    @Test
    public void shouldBeNotValidIfSubscriptionIdIsNotSet()
    {
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("subscription id must be greater than or equal to 0");

        command.validate();
    }
}
