package org.camunda.tngp.broker.wf.runtime.log.handler.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.camunda.tngp.bpmn.graph.ProcessGraph;
import org.camunda.tngp.broker.test.util.FluentAnswer;
import org.camunda.tngp.broker.wf.runtime.log.bpmn.BpmnFlowElementEventReader;
import org.camunda.tngp.broker.wf.runtime.log.bpmn.BpmnProcessEventWriter;
import org.camunda.tngp.graph.bpmn.BpmnAspect;
import org.camunda.tngp.graph.bpmn.ExecutionEventType;
import org.camunda.tngp.log.LogWriter;
import org.camunda.tngp.log.idgenerator.IdGenerator;
import org.camunda.tngp.log.idgenerator.impl.PrivateIdGenerator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class StartProcessHandlerTest
{

    @Mock
    protected BpmnFlowElementEventReader flowElementEventReader;

    @Mock
    protected ProcessGraph process;

    @Mock
    protected LogWriter logWriter;

    protected IdGenerator idGenerator;

    protected BpmnProcessEventWriter eventWriter;

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);
        eventWriter = mock(BpmnProcessEventWriter.class, new FluentAnswer());
        idGenerator = new PrivateIdGenerator(0);
    }

    @Test
    public void shouldWriteStartProcessEvent()
    {
        // given
        final StartProcessHandler startProcessHandler = new StartProcessHandler();
        startProcessHandler.setEventWriter(eventWriter);

        when(flowElementEventReader.event()).thenReturn(ExecutionEventType.EVT_OCCURRED);
        when(flowElementEventReader.flowElementId()).thenReturn(42);
        when(flowElementEventReader.key()).thenReturn(53L);
        when(flowElementEventReader.wfDefinitionId()).thenReturn(1234L);
        when(flowElementEventReader.wfInstanceId()).thenReturn(1701L);

        // when
        startProcessHandler.handle(flowElementEventReader, process, logWriter, idGenerator);

        // then
        verify(eventWriter).event(ExecutionEventType.PROC_INST_CREATED);
        verify(eventWriter).processId(1234L);
        verify(eventWriter).processInstanceId(1701L);
        verify(eventWriter).key(1701L);
        verify(eventWriter).initialElementId(42);

        verify(logWriter).write(eventWriter);
    }

    @Test
    public void shouldHandleStartProcessAspect()
    {
        // given
        final StartProcessHandler startProcessHandler = new StartProcessHandler();

        // when
        final BpmnAspect handledBpmnAspect = startProcessHandler.getHandledBpmnAspect();

        // then
        assertThat(handledBpmnAspect).isEqualTo(BpmnAspect.START_PROCESS);

    }
}