package org.camunda.tngp.client.cmd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.agrona.DirectBuffer;
import org.camunda.tngp.client.impl.cmd.wf.deploy.DeployBpmnResourceAckResponseHandler;
import org.camunda.tngp.protocol.wf.DeployBpmnResourceAckResponseReader;
import org.junit.Test;

public class DeployBpmnResourceResponseHandlerTest
{

    @Test
    public void testReadResponseBody()
    {
        // given
        final DeployBpmnResourceAckResponseHandler responseHandler = new DeployBpmnResourceAckResponseHandler();

        final DeployBpmnResourceAckResponseReader responseReaderMock = mock(DeployBpmnResourceAckResponseReader.class);
        responseHandler.setResponseReader(responseReaderMock);

        when(responseReaderMock.wfDefinitionId()).thenReturn(98765L);
        final DirectBuffer responseBuffer = mock(DirectBuffer.class);

        // when
        final WorkflowDefinition response = responseHandler.readResponse(responseBuffer, 15, 30);

        // then
        assertThat(response.getId()).isEqualTo(98765L);
        verify(responseReaderMock).wrap(responseBuffer, 15, 30);
    }
}
