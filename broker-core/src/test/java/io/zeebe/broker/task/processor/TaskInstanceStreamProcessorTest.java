/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.task.processor;

import static io.zeebe.broker.util.msgpack.MsgPackUtil.JSON_MAPPER;
import static io.zeebe.broker.util.msgpack.MsgPackUtil.MSGPACK_MAPPER;
import static io.zeebe.protocol.clientapi.EventType.TASK_EVENT;
import static io.zeebe.util.buffer.BufferUtil.wrapString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

import io.zeebe.broker.task.CreditsRequest;
import io.zeebe.broker.task.TaskSubscriptionManager;
import io.zeebe.broker.task.data.TaskEvent;
import io.zeebe.broker.task.data.TaskState;
import io.zeebe.broker.test.MockStreamProcessorController;
import io.zeebe.broker.transport.clientapi.CommandResponseWriter;
import io.zeebe.broker.transport.clientapi.SubscribedEventWriter;
import io.zeebe.broker.util.msgpack.MsgPackUtil;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.processor.StreamProcessorContext;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.clientapi.SubscriptionType;
import io.zeebe.test.util.FluentMock;
import io.zeebe.util.time.ClockUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TaskInstanceStreamProcessorTest
{
    public static final DirectBuffer TASK_TYPE = MsgPackUtil.utf8("foo");
    private static final int TERM = 3;

    private TaskInstanceStreamProcessor streamProcessor;

    private final Instant now = Instant.now();
    private final long lockTime = now.plus(Duration.ofMinutes(5)).toEpochMilli();

    @Mock
    private LogStream mockLogStream;

    @FluentMock
    private CommandResponseWriter mockResponseWriter;

    @FluentMock
    private SubscribedEventWriter mockSubscribedEventWriter;

    @Mock
    private TaskSubscriptionManager mockTaskSubscriptionManager;

    @Rule
    public MockStreamProcessorController<TaskEvent> mockController = new MockStreamProcessorController<>(
        TaskEvent.class,
        (t) -> t.setType(TASK_TYPE).setState(TaskState.CREATED),
        TASK_EVENT,
        0);

    @Before
    public void setup() throws InterruptedException, ExecutionException
    {
        MockitoAnnotations.initMocks(this);

        when(mockLogStream.getTopicName()).thenReturn(wrapString("test-topic"));
        when(mockLogStream.getPartitionId()).thenReturn(1);
        when(mockLogStream.getTerm()).thenReturn(TERM);

        streamProcessor = new TaskInstanceStreamProcessor(mockResponseWriter, mockSubscribedEventWriter, mockTaskSubscriptionManager);

        final StreamProcessorContext context = new StreamProcessorContext();
        context.setSourceStream(mockLogStream);
        context.setTargetStream(mockLogStream);

        mockController.initStreamProcessor(streamProcessor, context);

        ClockUtil.setCurrentTime(now);
    }

    @After
    public void cleanUp()
    {
        ClockUtil.reset();
        streamProcessor.onClose();
    }

    @Test
    public void shouldCreateTask()
    {
        // when
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.CREATED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);

        verify(mockResponseWriter).key(2L);
        verify(mockResponseWriter).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldLockTask() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // when
        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")),
            metadata -> metadata
                .requestStreamId(4)
                .subscriberKey(5L));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCKED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());

        verify(mockSubscribedEventWriter, times(1)).tryWriteMessage(4);
        verify(mockSubscribedEventWriter).subscriberKey(5L);
        verify(mockSubscribedEventWriter).subscriptionType(SubscriptionType.TASK_SUBSCRIPTION);
    }

    @Test
    public void shouldLockFailedTask() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")),
            metadata -> metadata
                .requestStreamId(4)
                .subscriberKey(5L));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")),
            metadata -> metadata
                .requestStreamId(6)
                .subscriberKey(7L));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCKED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());

        verify(mockSubscribedEventWriter, times(1)).tryWriteMessage(4);
        verify(mockSubscribedEventWriter, times(1)).tryWriteMessage(6);
        verify(mockSubscribedEventWriter).subscriberKey(7L);
    }

    @Test
    public void shouldLockExpiredTask()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")),
            metadata -> metadata
                .requestStreamId(4)
                .subscriberKey(5L));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")),
            metadata -> metadata
                .requestStreamId(6)
                .subscriberKey(7L));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCKED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());

        verify(mockSubscribedEventWriter, times(1)).tryWriteMessage(4);
        verify(mockSubscribedEventWriter, times(1)).tryWriteMessage(6);
        verify(mockSubscribedEventWriter).subscriberKey(7L);
    }

    @Test
    public void shouldCompleteTask() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldCompleteExpiredTask() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldMarkTaskAsFailed() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.FAILED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldExpireTaskLock()
    {
        // given
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        // when
        ClockUtil.setCurrentTime(lockTime);

        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_EXPIRED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldUpdateRetries() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CREATE)
                .setRetries(1));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner"))
                .setRetries(0));
        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.UPDATE_RETRIES)
                .setRetries(2));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.RETRIES_UPDATED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(3)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldCompensateLockRejection()
    {
        // given
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CREATE)
                .setRetries(1));

        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner-1")),
            metadata -> metadata
                .subscriberKey(1L));

        // when
        mockController.processEvent(2L,
            event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner-2")),
            metadata -> metadata
                .subscriberKey(2L));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_REJECTED);

        verify(mockTaskSubscriptionManager, times(1)).increaseSubscriptionCreditsAsync(new CreditsRequest(2L, 1));
    }

    @Test
    public void shouldCancelTaskIfCreated()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CANCEL));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.CANCELED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);
    }

    @Test
    public void shouldCancelTaskIfLocked()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CANCEL));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.CANCELED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);
    }

    @Test
    public void shouldCancelTaskIfFailed()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CANCEL));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.CANCELED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);
    }

    @Test
    public void shouldRejectLockTaskIfNotExist()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // when
        mockController.processEvent(4L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_REJECTED);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
        verify(mockSubscribedEventWriter, never()).tryWriteMessage(anyInt());
    }

    @Test
    public void shouldRejectLockTaskIfAlreadyLocked()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_REJECTED);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
        verify(mockSubscribedEventWriter, times(1)).tryWriteMessage(anyInt());
    }

    @Test
    public void shouldRejectCompleteTaskIfNotExists()
    {
        // when
        mockController.processEvent(4L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETE_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectCompleteTaskIfPayloadIsInvalid() throws Exception
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
            .setState(TaskState.LOCK)
            .setLockTime(lockTime)
            .setLockOwner(wrapString("owner")));

        // when
        final byte[] bytes = MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("'foo'"));
        final DirectBuffer buffer = new UnsafeBuffer(bytes);
        mockController.processEvent(2L, event -> event
            .setState(TaskState.COMPLETE)
            .setLockOwner(wrapString("owner"))
            .setPayload(buffer));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETE_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectCompleteTaskIfAlreadyCompleted()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETE_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(3)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectCompleteTaskIfNotLocked()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETE_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectCompleteTaskIfLockedBySomeoneElse()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner-1")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner-2")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.COMPLETE_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectMarkTaskAsFailedIfNotExists()
    {
        // when
        mockController.processEvent(4L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.FAIL_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectMarkTaskAsFailedIfAlreadyFailed()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.FAIL_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(3)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectMarkTaskAsFailedIfNotLocked()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.FAIL_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectMarkTaskAsFailedIfAlreadyCompleted()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.FAIL_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(3)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectMarkTaskAsFailedIfLockedBySomeoneElse()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner-1")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner-2")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.FAIL_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectExpireLockIfNotExists()
    {
        // when
        mockController.processEvent(4L, event -> event
                .setState(TaskState.EXPIRE_LOCK));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_EXPIRATION_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, never()).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectExpireLockIfAlreadyExpired()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_EXPIRATION_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectExpireLockIfNotLocked()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK)
                .setLockOwner(wrapString("owner")));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_EXPIRATION_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectExpireLockIfAlreadyCompleted()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_EXPIRATION_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectExpireLockIfAlreadyFailed()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.EXPIRE_LOCK));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.LOCK_EXPIRATION_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectUpdateRetriesIfNotExists()
    {
        // when
        mockController.processEvent(4L, event -> event
                .setState(TaskState.UPDATE_RETRIES)
                .setRetries(3));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.UPDATE_RETRIES_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(1)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectUpdateRetriesIfCompleted()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.UPDATE_RETRIES));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.UPDATE_RETRIES_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(3)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectUpdateRetriesIfLocked()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.UPDATE_RETRIES));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.UPDATE_RETRIES_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(2)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectUpdateRetriesIfRetriesLessThanOne() throws InterruptedException, ExecutionException
    {
        // given
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CREATE)
                .setRetries(1));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.FAIL)
                .setLockOwner(wrapString("owner"))
                .setRetries(0));
        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.UPDATE_RETRIES)
                .setLockOwner(wrapString("owner-2"))
                .setRetries(0));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.UPDATE_RETRIES_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);

        verify(mockResponseWriter, times(3)).tryWriteResponse(anyInt(), anyLong());
    }

    @Test
    public void shouldRejectCancelTaskIfCompleted()
    {
        // given
        mockController.processEvent(2L, event ->
            event.setState(TaskState.CREATE));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.LOCK)
                .setLockTime(lockTime)
                .setLockOwner(wrapString("owner")));

        mockController.processEvent(2L, event -> event
                .setState(TaskState.COMPLETE)
                .setLockOwner(wrapString("owner")));

        // when
        mockController.processEvent(2L, event -> event
                .setState(TaskState.CANCEL));

        // then
        assertThat(mockController.getLastWrittenEventValue().getState()).isEqualTo(TaskState.CANCEL_REJECTED);
        assertThat(mockController.getLastWrittenEventMetadata().getProtocolVersion()).isEqualTo(Protocol.PROTOCOL_VERSION);
        assertThat(mockController.getLastWrittenEventMetadata().getRaftTermId()).isEqualTo(TERM);
    }
}
