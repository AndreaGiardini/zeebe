package io.zeebe.client.task;

import static io.zeebe.test.broker.protocol.clientapi.ClientApiRule.DEFAULT_PARTITION_ID;
import static io.zeebe.test.broker.protocol.clientapi.ClientApiRule.DEFAULT_TOPIC_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;

import io.zeebe.client.TasksClient;
import io.zeebe.client.cmd.ClientCommandRejectedException;
import io.zeebe.client.event.TaskEvent;
import io.zeebe.client.event.impl.TaskEventImpl;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.util.ClientRule;
import io.zeebe.client.util.Events;
import io.zeebe.protocol.clientapi.EventType;
import io.zeebe.test.broker.protocol.brokerapi.ExecuteCommandRequest;
import io.zeebe.test.broker.protocol.brokerapi.StubBrokerRule;
import io.zeebe.util.time.ClockUtil;

public class CompleteTaskTest
{

    public ClientRule clientRule = new ClientRule();
    public StubBrokerRule brokerRule = new StubBrokerRule();

    @Rule
    public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(clientRule);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    protected TasksClient client;

    protected final MsgPackConverter converter = new MsgPackConverter();

    @Before
    public void setUp()
    {
        this.client = clientRule.getClient().tasks();
        ClockUtil.setCurrentTime(Instant.now());
    }

    @After
    public void tearDown()
    {
        ClockUtil.reset();
    }

    @Test
    public void shouldCompleteTask()
    {
        // given
        final TaskEventImpl baseEvent = Events.exampleTask();

        brokerRule.onExecuteCommandRequest(EventType.TASK_EVENT, "COMPLETE")
            .respondWith()
            .topicName(DEFAULT_TOPIC_NAME)
            .partitionId(DEFAULT_PARTITION_ID)
            .key(123)
            .event()
              .allOf((r) -> r.getCommand())
              .put("state", "COMPLETED")
              .done()
            .register();

        final String updatedPayload = "{\"fruit\":\"cherry\"}";

        // when
        final TaskEvent taskEvent = clientRule.tasks()
            .complete(baseEvent)
            .payload(updatedPayload)
            .execute();

        // then
        final ExecuteCommandRequest request = brokerRule.getReceivedCommandRequests().get(0);
        assertThat(request.eventType()).isEqualTo(EventType.TASK_EVENT);
        assertThat(request.topicName()).isEqualTo(DEFAULT_TOPIC_NAME);
        assertThat(request.partitionId()).isEqualTo(DEFAULT_PARTITION_ID);

        assertThat(request.getCommand()).containsOnly(
                entry("state", "COMPLETE"),
                entry("lockTime", baseEvent.getLockExpirationTime().toEpochMilli()),
                entry("lockOwner", baseEvent.getLockOwner()),
                entry("retries", baseEvent.getRetries()),
                entry("type", baseEvent.getType()),
                entry("headers", baseEvent.getHeaders()),
                entry("payload", converter.convertToMsgPack(updatedPayload)));

        assertThat(taskEvent.getMetadata().getEventKey()).isEqualTo(123L);
        assertThat(taskEvent.getMetadata().getTopicName()).isEqualTo(DEFAULT_TOPIC_NAME);
        assertThat(taskEvent.getMetadata().getPartitionId()).isEqualTo(DEFAULT_PARTITION_ID);

        assertThat(taskEvent.getState()).isEqualTo("COMPLETED");
        assertThat(taskEvent.getHeaders()).isEqualTo(baseEvent.getHeaders());
        assertThat(taskEvent.getLockExpirationTime()).isEqualTo(baseEvent.getLockExpirationTime());
        assertThat(taskEvent.getLockOwner()).isEqualTo(baseEvent.getLockOwner());
        assertThat(taskEvent.getRetries()).isEqualTo(baseEvent.getRetries());
        assertThat(taskEvent.getType()).isEqualTo(baseEvent.getType());
        assertThat(taskEvent.getPayload()).isEqualTo(updatedPayload);
    }

    @Test
    public void shouldThrowExceptionOnRejection()
    {
        // given
        final TaskEventImpl baseEvent = Events.exampleTask();

        brokerRule.onExecuteCommandRequest(EventType.TASK_EVENT, "COMPLETE")
            .respondWith()
            .topicName(DEFAULT_TOPIC_NAME)
            .partitionId(DEFAULT_PARTITION_ID)
            .key(r -> r.key())
            .event()
              .allOf((r) -> r.getCommand())
              .put("state", "COMPLETE_REJECTED")
              .done()
            .register();

        final String updatedPayload = "{\"fruit\":\"cherry\"}";

        // then
        exception.expect(ClientCommandRejectedException.class);
        exception.expectMessage("Command for event with key 79 was rejected by broker (COMPLETE_REJECTED)");

        // when
        clientRule.tasks()
            .complete(baseEvent)
            .payload(updatedPayload)
            .execute();
    }

    @Test
    public void shouldThrowExceptionIfBaseEventIsNull()
    {
        // given
        final String updatedPayload = "{\"fruit\":\"cherry\"}";

        // then
        exception.expect(RuntimeException.class);
        exception.expectMessage("base event must not be null");

        // when
        clientRule.tasks()
            .complete(null)
            .payload(updatedPayload)
            .execute();
    }

    @Test
    public void shouldSetPayloadAsStream()
    {
        // given
        final TaskEventImpl baseEvent = Events.exampleTask();

        brokerRule.onExecuteCommandRequest(EventType.TASK_EVENT, "COMPLETE")
            .respondWith()
            .topicName(DEFAULT_TOPIC_NAME)
            .partitionId(DEFAULT_PARTITION_ID)
            .key(123)
            .event()
              .allOf((r) -> r.getCommand())
              .put("state", "COMPLETED")
              .done()
            .register();

        final String updatedPayload = "{\"fruit\":\"cherry\"}";
        final ByteArrayInputStream inStream =
                new ByteArrayInputStream(updatedPayload.getBytes(StandardCharsets.UTF_8));

        // when
        clientRule.tasks()
            .complete(baseEvent)
            .payload(inStream)
            .execute();

        // then
        final ExecuteCommandRequest request = brokerRule.getReceivedCommandRequests().get(0);

        assertThat(request.getCommand()).contains(
                entry("payload", converter.convertToMsgPack(updatedPayload)));
    }

}
