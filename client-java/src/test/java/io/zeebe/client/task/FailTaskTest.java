package io.zeebe.client.task;

import static io.zeebe.test.broker.protocol.clientapi.ClientApiRule.DEFAULT_PARTITION_ID;
import static io.zeebe.test.broker.protocol.clientapi.ClientApiRule.DEFAULT_TOPIC_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;

import io.zeebe.client.TasksClient;
import io.zeebe.client.cmd.ClientCommandRejectedException;
import io.zeebe.client.event.TaskEvent;
import io.zeebe.client.event.impl.TaskEventImpl;
import io.zeebe.client.util.ClientRule;
import io.zeebe.client.util.Events;
import io.zeebe.protocol.clientapi.EventType;
import io.zeebe.test.broker.protocol.brokerapi.ExecuteCommandRequest;
import io.zeebe.test.broker.protocol.brokerapi.StubBrokerRule;

public class FailTaskTest
{

    public ClientRule clientRule = new ClientRule();
    public StubBrokerRule brokerRule = new StubBrokerRule();

    @Rule
    public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(clientRule);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    protected TasksClient client;


    @Test
    public void shouldFailTask()
    {
        // given
        final TaskEventImpl baseEvent = Events.exampleTask();

        brokerRule.onExecuteCommandRequest(EventType.TASK_EVENT, "FAIL")
            .respondWith()
            .topicName(DEFAULT_TOPIC_NAME)
            .partitionId(DEFAULT_PARTITION_ID)
            .key(123)
            .event()
              .allOf((r) -> r.getCommand())
              .put("state", "FAILED")
              .done()
            .register();

        // when
        final TaskEvent taskEvent = clientRule.tasks()
            .fail(baseEvent)
            .retries(4)
            .execute();

        // then
        final ExecuteCommandRequest request = brokerRule.getReceivedCommandRequests().get(0);
        assertThat(request.eventType()).isEqualTo(EventType.TASK_EVENT);
        assertThat(request.topicName()).isEqualTo(DEFAULT_TOPIC_NAME);
        assertThat(request.partitionId()).isEqualTo(DEFAULT_PARTITION_ID);

        assertThat(request.getCommand()).containsOnly(
                entry("state", "FAIL"),
                entry("lockTime", baseEvent.getLockExpirationTime().toEpochMilli()),
                entry("lockOwner", baseEvent.getLockOwner()),
                entry("retries", 4),
                entry("type", baseEvent.getType()),
                entry("headers", baseEvent.getHeaders()),
                entry("payload", baseEvent.getPayloadMsgPack()));

        assertThat(taskEvent.getMetadata().getEventKey()).isEqualTo(123L);
        assertThat(taskEvent.getMetadata().getTopicName()).isEqualTo(DEFAULT_TOPIC_NAME);
        assertThat(taskEvent.getMetadata().getPartitionId()).isEqualTo(DEFAULT_PARTITION_ID);

        assertThat(taskEvent.getState()).isEqualTo("FAILED");
        assertThat(taskEvent.getHeaders()).isEqualTo(baseEvent.getHeaders());
        assertThat(taskEvent.getLockExpirationTime()).isEqualTo(baseEvent.getLockExpirationTime());
        assertThat(taskEvent.getLockOwner()).isEqualTo(baseEvent.getLockOwner());
        assertThat(taskEvent.getType()).isEqualTo(baseEvent.getType());
        assertThat(taskEvent.getPayload()).isEqualTo(baseEvent.getPayload());
        assertThat(taskEvent.getRetries()).isEqualTo(4);
    }

    @Test
    public void shouldThrowExceptionOnRejection()
    {
        // given
        final TaskEventImpl baseEvent = Events.exampleTask();

        brokerRule.onExecuteCommandRequest(EventType.TASK_EVENT, "FAIL")
            .respondWith()
            .topicName(DEFAULT_TOPIC_NAME)
            .partitionId(DEFAULT_PARTITION_ID)
            .key((r) -> r.key())
            .event()
              .allOf((r) -> r.getCommand())
              .put("state", "FAIL_REJECTED")
              .done()
            .register();

        // then
        exception.expect(ClientCommandRejectedException.class);
        exception.expectMessage("Command for event with key 79 was rejected by broker (FAIL_REJECTED)");

        // when
        clientRule.tasks()
            .fail(baseEvent)
            .retries(5)
            .execute();
    }

    @Test
    public void shouldThrowExceptionIfBaseEventIsNull()
    {
        // then
        exception.expect(RuntimeException.class);
        exception.expectMessage("base event must not be null");

        // when
        clientRule.tasks()
            .fail(null)
            .retries(5)
            .execute();
    }

}
