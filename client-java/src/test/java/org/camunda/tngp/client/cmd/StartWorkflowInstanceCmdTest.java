package org.camunda.tngp.client.cmd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import org.camunda.tngp.client.impl.ClientCmdExecutor;
import org.camunda.tngp.client.impl.data.JacksonDocumentConverter;
import org.camunda.tngp.client.impl.cmd.ClientResponseHandler;
import org.camunda.tngp.client.impl.cmd.StartWorkflowInstanceCmdImpl;
import org.camunda.tngp.client.impl.cmd.StartWorkflowInstanceResponseHandler;
import org.camunda.tngp.client.impl.cmd.wf.start.StartWorkflowInstanceRequestWriter;
import org.camunda.tngp.client.impl.data.DocumentConverter;
import org.camunda.tngp.util.buffer.BufferWriter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class StartWorkflowInstanceCmdTest
{

    @Mock
    protected StartWorkflowInstanceRequestWriter requestWriter;

    @Mock
    protected ClientCmdExecutor commandExecutor;

    protected DocumentConverter documentConverter = JacksonDocumentConverter.newDefaultConverter();

    protected static final byte[] WORKFLOW_KEY_BYTES = "bar".getBytes(StandardCharsets.UTF_8);

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void shouldSetWorkflowId()
    {
        // given
        final StartWorkflowInstanceCmdImpl command = new StartWorkflowInstanceCmdImpl(commandExecutor, documentConverter);
        command.setRequestWriter(requestWriter);
        final StartWorkflowInstanceCmd apiCommand = command;

        // when
        apiCommand.workflowDefinitionId(1234L);

        // then
        verify(requestWriter).wfDefinitionId(1234L);
    }

    @Test
    public void shouldSetWorkflowKeyAsBytes()
    {
        // given
        final StartWorkflowInstanceCmdImpl command = new StartWorkflowInstanceCmdImpl(commandExecutor, documentConverter);
        command.setRequestWriter(requestWriter);
        final StartWorkflowInstanceCmd apiCommand = command;

        // when
        apiCommand.workflowDefinitionKey(WORKFLOW_KEY_BYTES);

        // then
        verify(requestWriter).wfDefinitionKey(WORKFLOW_KEY_BYTES);
    }

    @Test
    public void shouldSetWorkflowKeyAsString()
    {
        // given
        final StartWorkflowInstanceCmdImpl command = new StartWorkflowInstanceCmdImpl(commandExecutor, documentConverter);
        command.setRequestWriter(requestWriter);
        final StartWorkflowInstanceCmd apiCommand = command;

        // when
        apiCommand.workflowDefinitionKey("bar");

        // then
        verify(requestWriter).wfDefinitionKey(WORKFLOW_KEY_BYTES);
    }

    @Test
    public void testRequestWriter()
    {
        // given
        final StartWorkflowInstanceCmdImpl command = new StartWorkflowInstanceCmdImpl(commandExecutor, documentConverter);

        // when
        final BufferWriter requestWriter = command.getRequestWriter();

        // then
        assertThat(requestWriter).isInstanceOf(StartWorkflowInstanceRequestWriter.class);
    }

    @Test
    public void testResponseHandlers()
    {
        // given
        final StartWorkflowInstanceCmdImpl command = new StartWorkflowInstanceCmdImpl(commandExecutor, documentConverter);

        // when
        final ClientResponseHandler<WorkflowInstance> responseHandler = command.getResponseHandler();

        // then
        assertThat(responseHandler).isInstanceOf(StartWorkflowInstanceResponseHandler.class);
    }

}
