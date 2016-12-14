package org.camunda.tngp.client.cmd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.camunda.tngp.client.ClientCommand;
import org.camunda.tngp.client.impl.ClientChannelResolver;
import org.camunda.tngp.client.impl.ClientCmdExecutor;
import org.camunda.tngp.client.impl.cmd.AbstractCmdImpl;
import org.camunda.tngp.client.impl.cmd.ClientResponseHandler;
import org.camunda.tngp.transport.requestresponse.client.PooledTransportRequest;
import org.camunda.tngp.transport.requestresponse.client.TransportConnection;
import org.camunda.tngp.transport.requestresponse.client.TransportConnectionPool;
import org.camunda.tngp.transport.singlemessage.DataFramePool;
import org.camunda.tngp.util.buffer.RequestWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Test cases for aspects common to all commands
 *
 * @author Lindhauer
 */
public class CommandExecutorTest
{

    public static final int REQUEST_LENGTH = 113;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Mock
    protected MutableDirectBuffer requestBuffer;

    @Mock
    protected DirectBuffer responseBuffer;

    @Mock
    protected PooledTransportRequest request;

    @Mock
    protected TransportConnection connection;

    @Mock
    protected ClientResponseHandler<Object> responseHandler;

    @Mock
    protected RequestWriter requestWriter;

    @Mock
    protected ClientChannelResolver clientChannelResolver;

    protected ClientCmdExecutor commandExecutor;

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);

        when(request.getClaimedRequestBuffer()).thenReturn(requestBuffer);
        when(request.getClaimedOffset()).thenReturn(84);
        when(request.getResponseBuffer()).thenReturn(responseBuffer);
        when(request.getResponseLength()).thenReturn(99);
        when(request.getRequestTimeout()).thenReturn(12345L);

        when(connection.openRequest(anyInt(), anyInt())).thenReturn(request);

        when(requestWriter.getLength()).thenReturn(REQUEST_LENGTH);

        when(clientChannelResolver.getChannelIdForCmd(any(AbstractCmdImpl.class))).thenReturn(73);

        commandExecutor = new ClientCmdExecutor(mock(TransportConnectionPool.class), mock(DataFramePool.class), clientChannelResolver);
    }

    @Test
    public void testExecuteAsync()
    {
        // given
        final ExampleCmd cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);

        // when
        commandExecutor.executeAsync(cmd, connection);

        // then
        final InOrder inOrder = Mockito.inOrder(connection, request, requestWriter);

        inOrder.verify(requestWriter).validate();
        inOrder.verify(requestWriter).getLength();
        inOrder.verify(connection).openRequest(73, REQUEST_LENGTH);
        inOrder.verify(requestWriter).write(requestBuffer, 84);
        inOrder.verify(request).commit();

        verify(request, never()).awaitResponse();
        verify(request, never()).awaitResponse(anyLong(), any());
        verify(request, never()).close();
    }

    @Test
    public void testExecuteAsyncAndAwaitResult() throws InterruptedException, ExecutionException
    {
        // given
        final ExampleCmd cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);
        final WorkflowDefinition expectedResult = mock(WorkflowDefinition.class);

        when(request.awaitResponse(anyLong(), any())).thenReturn(true);
        when(responseHandler.readResponse(any(), anyInt(), anyInt())).thenReturn(expectedResult);

        final Future<Object> asyncResult = cmd.executeAsync(connection);

        // when
        final Object returnedResult = asyncResult.get();

        // then
        assertThat(returnedResult).isSameAs(expectedResult);

        final InOrder inOrder = Mockito.inOrder(request, responseHandler);
        inOrder.verify(request).awaitResponse(12345L, TimeUnit.MILLISECONDS);
        inOrder.verify(responseHandler).readResponse(responseBuffer, 0, 99);
        inOrder.verify(request).close();
    }

    @Test
    public void testAwaitTime() throws InterruptedException, ExecutionException, TimeoutException
    {
        // given
        final ExampleCmd cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);
        final WorkflowDefinition expectedResult = mock(WorkflowDefinition.class);

        when(request.awaitResponse(anyLong(), any())).thenReturn(true);
        when(responseHandler.readResponse(any(), anyInt(), anyInt())).thenReturn(expectedResult);

        final Future<Object> asyncResult = cmd.executeAsync(connection);

        // when
        final Object returnedResult = asyncResult.get(1234, TimeUnit.MINUTES);

        // then
        assertThat(returnedResult).isSameAs(expectedResult);

        final InOrder inOrder = Mockito.inOrder(request, responseHandler);
        inOrder.verify(request).awaitResponse(1234, TimeUnit.MINUTES);
        inOrder.verify(responseHandler).readResponse(responseBuffer, 0, 99);
        inOrder.verify(request).close();
    }

    @Test
    public void testExecuteSync()
    {
        // given
        final ExampleCmd cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);
        final WorkflowDefinition expectedResult = mock(WorkflowDefinition.class);

        when(request.awaitResponse(anyLong(), any())).thenReturn(true);
        when(responseHandler.readResponse(any(), anyInt(), anyInt())).thenReturn(expectedResult);

        // when
        final Object returnedResult = cmd.execute(connection);

        // then
        assertThat(returnedResult).isSameAs(expectedResult);

        final InOrder inOrder = Mockito.inOrder(connection, requestBuffer, request, requestWriter, responseHandler);

        inOrder.verify(requestWriter).validate();
        inOrder.verify(requestWriter).getLength();
        inOrder.verify(connection).openRequest(73, REQUEST_LENGTH);
        inOrder.verify(requestWriter).write(requestBuffer, 84);
        inOrder.verify(request).commit();

        inOrder.verify(request).awaitResponse(12345L, TimeUnit.MILLISECONDS);
        inOrder.verify(responseHandler).readResponse(responseBuffer, 0, 99);
        inOrder.verify(request).close();
    }

    @Test
    public void testExecuteAsyncAndGetResponseAvailable() throws InterruptedException, ExecutionException
    {
        // given
        final ClientCommand<Object> cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);
        final WorkflowDefinition expectedResult = mock(WorkflowDefinition.class);

        when(request.awaitResponse(anyLong(), any())).thenReturn(true);
        when(responseHandler.readResponse(any(), anyInt(), anyInt())).thenReturn(expectedResult);

        final Future<Object> asyncResult = cmd.executeAsync(connection);

        // when
        final Object returnedResult = asyncResult.get();

        // then
        assertThat(returnedResult).isSameAs(expectedResult);

        final InOrder inOrder = Mockito.inOrder(request, responseHandler);
        inOrder.verify(responseHandler).readResponse(responseBuffer, 0, 99);
        inOrder.verify(request).close();
    }

    @Test
    public void testExecuteAsyncAndGetResponseUnavailable() throws InterruptedException, ExecutionException
    {
        // given
        final ClientCommand<Object> cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);


        final Future<Object> asyncResult = cmd.executeAsync(connection);

        // then
        exception.expect(TimeoutException.class);
        exception.expectMessage("Provided timeout of 12345 ms reached");

        // when
        when(request.awaitResponse(anyLong(), any())).thenReturn(false);
        asyncResult.get();
    }

    @Test
    public void testPollResponseAvailable()
    {
        // given
        final ClientCommand<Object> cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);

        final Future<Object> asyncResult = cmd.executeAsync(connection);

        when(request.pollResponse()).thenReturn(true);

        // when
        final boolean isAvailable = asyncResult.isDone();

        // then
        assertThat(isAvailable).isTrue();

        verify(request, times(1)).pollResponse();
    }

    @Test
    public void testPollResponseUnavailable()
    {
        // given
        final ClientCommand<Object> cmd = new ExampleCmd(commandExecutor, requestWriter, responseHandler);

        final Future<Object> asyncResult = cmd.executeAsync(connection);

        when(request.pollResponse()).thenReturn(false);

        // when
        final boolean isAvailable = asyncResult.isDone();

        // then
        assertThat(isAvailable).isFalse();

        verify(request, times(1)).pollResponse();
    }

    public static class ExampleCmd extends AbstractCmdImpl<Object>
    {

        protected RequestWriter requestWriter;

        public ExampleCmd(final ClientCmdExecutor cmdExecutor, final RequestWriter requestWriter, final ClientResponseHandler<Object> responseHandler)
        {
            super(cmdExecutor, responseHandler);
            this.requestWriter = requestWriter;
        }

        @Override
        public RequestWriter getRequestWriter()
        {
            return requestWriter;
        }
    }

}
