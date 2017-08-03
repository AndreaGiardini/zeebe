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
package io.zeebe.broker.it.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;

import io.zeebe.broker.it.ClientRule;
import io.zeebe.broker.it.EmbeddedBrokerRule;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.cmd.BrokerErrorException;
import io.zeebe.client.event.TaskEvent;

/**
 * Tests the entire cycle of task creation, polling and completion as a smoke test for when something gets broken
 *
 * @author Lindhauer
 */
public class TaskQueueTest
{
    public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();

    public ClientRule clientRule = new ClientRule();

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(brokerRule)
        .around(clientRule);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public Timeout testTimeout = Timeout.seconds(10);


    @Test
    public void shouldCreateTask()
    {
        final TaskEvent taskEvent = clientRule.tasks().create(clientRule.getDefaultTopic(), "foo")
            .addHeader("k1", "a")
            .addHeader("k2", "b")
            .payload("{ \"payload\" : 123 }")
            .execute();

        assertThat(taskEvent).isNotNull();
        assertThat(taskEvent.getMetadata().getEventKey()).isGreaterThanOrEqualTo(0);
    }

    @Test
    public void shouldFailCreateTaskIfTopicNameIsNotValid()
    {
        final ZeebeClient client = clientRule.getClient();

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Cannot execute request (timeout). " +
                "Request was: [ topic = unknown-topic, partition = 0, event type = TASK]. " +
                "Contacted brokers: []");

        client.tasks().create("unknown-topic", "foo")
            .addHeader("k1", "a")
            .addHeader("k2", "b")
            .payload("{ \"payload\" : 123 }")
            .execute();
    }

    @Test
    @Ignore
    public void testCannotCompleteUnlockedTask()
    {
        final TaskEvent task = clientRule.tasks().create(clientRule.getDefaultTopic(), "bar")
            .payload("{}")
            .execute();

        thrown.expect(BrokerErrorException.class);
        thrown.expectMessage("Task does not exist or is not locked");

        clientRule.tasks().complete(task).execute();
    }
}
