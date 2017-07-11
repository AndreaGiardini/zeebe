package io.zeebe.broker.workflow.processor;

import static io.zeebe.broker.util.payload.PayloadUtil.isNilPayload;
import static io.zeebe.broker.util.payload.PayloadUtil.isValidPayload;
import static io.zeebe.protocol.clientapi.EventType.TASK_EVENT;
import static io.zeebe.protocol.clientapi.EventType.WORKFLOW_EVENT;
import static org.agrona.BitUtil.SIZE_OF_CHAR;

import java.util.EnumMap;
import java.util.Map;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import io.zeebe.broker.Constants;
import io.zeebe.broker.logstreams.BrokerEventMetadata;
import io.zeebe.broker.logstreams.processor.HashIndexSnapshotSupport;
import io.zeebe.broker.logstreams.processor.MetadataFilter;
import io.zeebe.broker.task.data.TaskEvent;
import io.zeebe.broker.task.data.TaskEventType;
import io.zeebe.broker.task.data.TaskHeaders;
import io.zeebe.broker.transport.clientapi.CommandResponseWriter;
import io.zeebe.broker.util.msgpack.value.ArrayValueIterator;
import io.zeebe.broker.workflow.data.DeployedWorkflow;
import io.zeebe.broker.workflow.data.WorkflowDeploymentEvent;
import io.zeebe.broker.workflow.data.WorkflowInstanceEvent;
import io.zeebe.broker.workflow.data.WorkflowInstanceEventType;
import io.zeebe.broker.workflow.graph.model.BpmnAspect;
import io.zeebe.broker.workflow.graph.model.ExecutableEndEvent;
import io.zeebe.broker.workflow.graph.model.ExecutableFlowElement;
import io.zeebe.broker.workflow.graph.model.ExecutableFlowNode;
import io.zeebe.broker.workflow.graph.model.ExecutableSequenceFlow;
import io.zeebe.broker.workflow.graph.model.ExecutableServiceTask;
import io.zeebe.broker.workflow.graph.model.ExecutableStartEvent;
import io.zeebe.broker.workflow.graph.model.ExecutableWorkflow;
import io.zeebe.broker.workflow.graph.model.metadata.TaskMetadata;
import io.zeebe.broker.workflow.graph.model.metadata.TaskMetadata.TaskHeader;
import io.zeebe.broker.workflow.graph.transformer.BpmnTransformer;
import io.zeebe.broker.workflow.index.ActivityInstanceIndex;
import io.zeebe.broker.workflow.index.PayloadCache;
import io.zeebe.broker.workflow.index.WorkflowDeploymentCache;
import io.zeebe.broker.workflow.index.WorkflowInstanceIndex;
import io.zeebe.hashindex.Bytes2LongHashIndex;
import io.zeebe.logstreams.log.BufferedLogStreamReader;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.log.LogStreamBatchWriter;
import io.zeebe.logstreams.log.LogStreamBatchWriter.LogEntryBuilder;
import io.zeebe.logstreams.log.LogStreamBatchWriterImpl;
import io.zeebe.logstreams.log.LogStreamReader;
import io.zeebe.logstreams.log.LogStreamWriter;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.processor.EventProcessor;
import io.zeebe.logstreams.processor.StreamProcessor;
import io.zeebe.logstreams.processor.StreamProcessorContext;
import io.zeebe.logstreams.snapshot.ComposedSnapshot;
import io.zeebe.logstreams.spi.SnapshotSupport;
import io.zeebe.msgpack.mapping.Mapping;
import io.zeebe.msgpack.mapping.MappingException;
import io.zeebe.msgpack.mapping.MappingProcessor;
import io.zeebe.protocol.clientapi.EventType;
import io.zeebe.util.actor.Actor;

public class WorkflowInstanceStreamProcessor implements StreamProcessor
{
    private static final int SIZE_OF_PROCESS_ID = BpmnTransformer.ID_MAX_LENGTH * SIZE_OF_CHAR;
    private static final UnsafeBuffer EMPTY_TASK_TYPE = new UnsafeBuffer("".getBytes());

    // processors ////////////////////////////////////
    protected final DeployedWorkflowEventProcessor deployedWorkflowEventProcessor = new DeployedWorkflowEventProcessor();

    protected final CreateWorkflowInstanceEventProcessor createWorkflowInstanceEventProcessor = new CreateWorkflowInstanceEventProcessor();
    protected final WorkflowInstanceCreatedEventProcessor workflowInstanceCreatedEventProcessor = new WorkflowInstanceCreatedEventProcessor();
    protected final CancelWorkflowInstanceProcessor cancelWorkflowInstanceProcessor = new CancelWorkflowInstanceProcessor();

    protected final UpdatePayloadProcessor updatePayloadProcessor = new UpdatePayloadProcessor();

    protected final EventProcessor sequenceFlowTakenEventProcessor = new ActiveWorkflowInstanceProcessor(new SequenceFlowTakenEventProcessor());
    protected final EventProcessor activityReadyEventProcessor = new ActiveWorkflowInstanceProcessor(new ActivityReadyEventProcessor());
    protected final EventProcessor activityActivatedEventProcessor = new ActiveWorkflowInstanceProcessor(new ActivityActivatedEventProcessor());
    protected final EventProcessor activityCompletingEventProcessor = new ActiveWorkflowInstanceProcessor(new ActivityCompletingEventProcessor());

    protected final EventProcessor taskCompletedEventProcessor = new TaskCompletedEventProcessor();
    protected final EventProcessor taskCreatedEventProcessor = new TaskCreatedProcessor();

    protected final Map<BpmnAspect, EventProcessor> aspectHandlers;
    {
        aspectHandlers = new EnumMap<>(BpmnAspect.class);

        aspectHandlers.put(BpmnAspect.TAKE_SEQUENCE_FLOW, new ActiveWorkflowInstanceProcessor(new TakeSequenceFlowAspectHandler()));
        aspectHandlers.put(BpmnAspect.CONSUME_TOKEN, new ActiveWorkflowInstanceProcessor(new ConsumeTokenAspectHandler()));
    }

    // data //////////////////////////////////////////

    protected final BrokerEventMetadata sourceEventMetadata = new BrokerEventMetadata();
    protected final BrokerEventMetadata targetEventMetadata = new BrokerEventMetadata();

    protected final WorkflowDeploymentEvent deploymentEvent = new WorkflowDeploymentEvent();
    protected final WorkflowInstanceEvent workflowInstanceEvent = new WorkflowInstanceEvent();
    protected final TaskEvent taskEvent = new TaskEvent();

    // internal //////////////////////////////////////

    protected final CommandResponseWriter responseWriter;

    protected final WorkflowInstanceIndex workflowInstanceIndex;
    protected final ActivityInstanceIndex activityInstanceIndex;
    protected final WorkflowDeploymentCache workflowDeploymentCache;
    protected final PayloadCache payloadCache;

    /**
     * An hash index which contains as key the BPMN process id and as value the corresponding latest definition version.
     */
    protected final Bytes2LongHashIndex latestWorkflowVersionIndex;

    protected final ComposedSnapshot composedSnapshot;

    protected LogStreamReader logStreamReader;
    protected LogStreamBatchWriter logStreamBatchWriter;

    protected DirectBuffer logStreamTopicName;
    protected int logStreamPartitionId;
    protected int streamProcessorId;
    protected long eventKey;
    protected long eventPosition;

    protected final MappingProcessor payloadMappingProcessor;

    protected LogStream targetStream;

    public WorkflowInstanceStreamProcessor(
            CommandResponseWriter responseWriter,
            int deploymentCacheSize,
            int payloadCacheSize)
    {
        this.responseWriter = responseWriter;
        this.logStreamReader = new BufferedLogStreamReader();

        this.latestWorkflowVersionIndex = new Bytes2LongHashIndex(8388608, 8, SIZE_OF_PROCESS_ID);

        this.workflowDeploymentCache = new WorkflowDeploymentCache(deploymentCacheSize, logStreamReader);
        this.payloadCache = new PayloadCache(payloadCacheSize, logStreamReader);

        this.workflowInstanceIndex = new WorkflowInstanceIndex();
        this.activityInstanceIndex = new ActivityInstanceIndex();

        this.payloadMappingProcessor = new MappingProcessor(4096);

        this.composedSnapshot = new ComposedSnapshot(
                new HashIndexSnapshotSupport<>(latestWorkflowVersionIndex),
                workflowInstanceIndex.getSnapshotSupport(),
                activityInstanceIndex.getSnapshotSupport(),
                workflowDeploymentCache.getSnapshotSupport(),
                payloadCache.getSnapshotSupport());

    }

    @Override
    public int getPriority(long now)
    {
        return Actor.PRIORITY_HIGH;
    }

    @Override
    public SnapshotSupport getStateResource()
    {
        return composedSnapshot;
    }

    @Override
    public void onOpen(StreamProcessorContext context)
    {
        final LogStream sourceStream = context.getSourceStream();
        this.logStreamTopicName = sourceStream.getTopicName();
        this.logStreamPartitionId = sourceStream.getPartitionId();
        this.streamProcessorId = context.getId();

        this.logStreamReader.wrap(sourceStream);
        this.logStreamBatchWriter = new LogStreamBatchWriterImpl(context.getTargetStream());

        this.targetStream = context.getTargetStream();
    }

    @Override
    public void onClose()
    {
        latestWorkflowVersionIndex.close();
        workflowInstanceIndex.close();
        activityInstanceIndex.close();
        workflowDeploymentCache.close();
        payloadCache.close();
    }

    public static MetadataFilter eventFilter()
    {
        return m -> m.getEventType() == EventType.DEPLOYMENT_EVENT
                || m.getEventType() == EventType.WORKFLOW_EVENT
                || m.getEventType() == EventType.TASK_EVENT;
    }

    @Override
    public EventProcessor onEvent(LoggedEvent event)
    {
        reset();

        eventKey = event.getKey();
        eventPosition = event.getPosition();
        event.readMetadata(sourceEventMetadata);

        EventProcessor eventProcessor = null;
        switch (sourceEventMetadata.getEventType())
        {
            case DEPLOYMENT_EVENT:
                eventProcessor = onDeploymentEvent(event);
                break;

            case WORKFLOW_EVENT:
                eventProcessor = onWorkflowEvent(event);
                break;

            case TASK_EVENT:
                eventProcessor = onTaskEvent(event);
                break;

            default:
                break;
        }

        return eventProcessor;
    }

    protected void reset()
    {
        sourceEventMetadata.reset();

        deploymentEvent.reset();
        workflowInstanceEvent.reset();
        taskEvent.reset();

        workflowInstanceIndex.reset();
        activityInstanceIndex.reset();
    }

    protected EventProcessor onDeploymentEvent(LoggedEvent event)
    {
        EventProcessor eventProcessor = null;

        event.readValue(deploymentEvent);

        switch (deploymentEvent.getEventType())
        {
            case DEPLOYMENT_CREATED:
                eventProcessor = deployedWorkflowEventProcessor;
                break;

            default:
                break;
        }

        return eventProcessor;
    }

    protected EventProcessor onWorkflowEvent(LoggedEvent event)
    {
        event.readValue(workflowInstanceEvent);

        EventProcessor eventProcessor = null;
        switch (workflowInstanceEvent.getEventType())
        {
            case CREATE_WORKFLOW_INSTANCE:
                eventProcessor = createWorkflowInstanceEventProcessor;
                break;

            case WORKFLOW_INSTANCE_CREATED:
                eventProcessor = workflowInstanceCreatedEventProcessor;
                break;

            case CANCEL_WORKFLOW_INSTANCE:
                eventProcessor = cancelWorkflowInstanceProcessor;
                break;

            case SEQUENCE_FLOW_TAKEN:
                eventProcessor = sequenceFlowTakenEventProcessor;
                break;

            case ACTIVITY_READY:
                eventProcessor = activityReadyEventProcessor;
                break;

            case ACTIVITY_ACTIVATED:
                eventProcessor = activityActivatedEventProcessor;
                break;

            case ACTIVITY_COMPLETING:
                eventProcessor = activityCompletingEventProcessor;
                break;

            case START_EVENT_OCCURRED:
            case END_EVENT_OCCURRED:
            case ACTIVITY_COMPLETED:
            {
                final ExecutableFlowNode currentActivity = getCurrentActivity();
                eventProcessor = aspectHandlers.get(currentActivity.getBpmnAspect());
                break;
            }

            case UPDATE_PAYLOAD:
                eventProcessor = updatePayloadProcessor;
                break;

            default:
                break;
        }

        return eventProcessor;
    }

    protected EventProcessor onTaskEvent(LoggedEvent event)
    {
        EventProcessor eventProcessor = null;

        event.readValue(taskEvent);

        switch (taskEvent.getEventType())
        {
            case CREATED:
                eventProcessor = taskCreatedEventProcessor;
                break;

            case COMPLETED:
                eventProcessor = taskCompletedEventProcessor;
                break;

            default:
                break;
        }

        return eventProcessor;
    }

    protected void lookupWorkflowInstanceEvent(long position)
    {
        final boolean found = logStreamReader.seek(position);
        if (found && logStreamReader.hasNext())
        {
            final LoggedEvent event = logStreamReader.next();

            workflowInstanceEvent.reset();
            event.readValue(workflowInstanceEvent);
        }
        else
        {
            throw new IllegalStateException("workflow instance event not found.");
        }
    }

    protected <T extends ExecutableFlowElement> T getCurrentActivity()
    {
        final DirectBuffer bpmnProcessId = workflowInstanceEvent.getBpmnProcessId();
        final int version = workflowInstanceEvent.getVersion();

        final ExecutableWorkflow workflow = workflowDeploymentCache.getWorkflow(workflowInstanceEvent.getWorkflowKey());

        final DirectBuffer currentActivityId = workflowInstanceEvent.getActivityId();

        return workflow.getChildById(currentActivityId);
    }

    protected long writeWorkflowEvent(LogStreamWriter writer)
    {
        targetEventMetadata.reset();
        targetEventMetadata
                .protocolVersion(Constants.PROTOCOL_VERSION)
                .eventType(WORKFLOW_EVENT)
                .raftTermId(targetStream.getTerm());

        // don't forget to set the key or use positionAsKey
        return writer
                .metadataWriter(targetEventMetadata)
                .valueWriter(workflowInstanceEvent)
                .tryWrite();
    }

    protected long writeTaskEvent(LogStreamWriter writer)
    {
        targetEventMetadata.reset();
        targetEventMetadata
                .protocolVersion(Constants.PROTOCOL_VERSION)
                .eventType(TASK_EVENT)
                .raftTermId(targetStream.getTerm());

        // don't forget to set the key or use positionAsKey
        return writer
                .metadataWriter(targetEventMetadata)
                .valueWriter(taskEvent)
                .tryWrite();
    }

    protected boolean sendWorkflowInstanceResponse()
    {
        return responseWriter
                .topicName(logStreamTopicName)
                .partitionId(logStreamPartitionId)
                .key(eventKey)
                .eventWriter(workflowInstanceEvent)
                .tryWriteResponse(sourceEventMetadata.getRequestStreamId(), sourceEventMetadata.getRequestId());
    }

    private final class CreateWorkflowInstanceEventProcessor implements EventProcessor
    {
        @Override
        public void processEvent()
        {
            final DirectBuffer bpmnProcessId = workflowInstanceEvent.getBpmnProcessId();

            int version = workflowInstanceEvent.getVersion();
            if (version == -1)
            {
                version = (int) latestWorkflowVersionIndex.get(bpmnProcessId, 0, bpmnProcessId.capacity(), -1);
            }

            WorkflowInstanceEventType newEventType = WorkflowInstanceEventType.WORKFLOW_INSTANCE_REJECTED;

            final DirectBuffer payload = workflowInstanceEvent.getPayload();
            if (isNilPayload(payload) || isValidPayload(payload))
            {
                if (workflowDeploymentCache.hasDeployedWorkflow(bpmnProcessId, version))
                {
                    newEventType = WorkflowInstanceEventType.WORKFLOW_INSTANCE_CREATED;
                }
            }

            workflowInstanceEvent
                    .setEventType(newEventType)
                    .setWorkflowInstanceKey(eventKey)
                    .setWorkflowKey(workflowInstanceEvent.getWorkflowKey())
                    .setVersion(version);
        }

        @Override
        public boolean executeSideEffects()
        {
            return sendWorkflowInstanceResponse();
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.key(eventKey));
        }
    }

    private final class WorkflowInstanceCreatedEventProcessor implements EventProcessor
    {
        @Override
        public void processEvent()
        {
            final ExecutableWorkflow workflow = workflowDeploymentCache.getWorkflow(workflowInstanceEvent.getWorkflowKey());

            final ExecutableStartEvent startEvent = workflow.getScopeStartEvent();
            final DirectBuffer activityId = startEvent.getId();

            workflowInstanceEvent
                .setEventType(WorkflowInstanceEventType.START_EVENT_OCCURRED)
                .setWorkflowInstanceKey(eventKey)
                .setActivityId(activityId)
                .setWorkflowKey(workflow.getWorkflowKey());
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.positionAsKey());
        }

        @Override
        public void updateState()
        {
            workflowInstanceIndex
                .newWorkflowInstance(eventKey)
                .setPosition(eventPosition)
                .setActiveTokenCount(1)
                .setActivityKey(-1L)
                .write();
        }
    }

    private final class ActivityReadyEventProcessor implements EventProcessor
    {
        private DirectBuffer sourcePayload;

        @Override
        public void processEvent()
        {
            final ExecutableFlowElement activty = getCurrentActivity();

            if (activty instanceof ExecutableServiceTask)
            {
                final ExecutableServiceTask serviceTask = (ExecutableServiceTask) activty;

                workflowInstanceEvent.setEventType(WorkflowInstanceEventType.ACTIVITY_ACTIVATED);

                try
                {
                    setWorkflowInstancePayload(serviceTask.getIoMapping().getInputMappings());
                }
                catch (Exception e)
                {
                    // update the index in any case because further processors based on it (#311 should improve behavior)
                    updateState();
                    // re-throw the exception to create the incident
                    throw e;
                }
            }
            else
            {
                throw new RuntimeException("Currently not supported. An activity must be of type service task.");
            }
        }

        private void setWorkflowInstancePayload(Mapping[] mappings)
        {
            sourcePayload = workflowInstanceEvent.getPayload();
            // only if we have no default mapping we have to use the mapping processor
            if (mappings.length > 0)
            {
                final int resultLen = payloadMappingProcessor.extract(sourcePayload, mappings);
                final MutableDirectBuffer buffer = payloadMappingProcessor.getResultBuffer();
                workflowInstanceEvent.setPayload(buffer, 0, resultLen);
            }
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.key(eventKey));
        }

        @Override
        public void updateState()
        {
            workflowInstanceIndex
                .wrapWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                .setActivityKey(eventKey)
                .write();

            activityInstanceIndex
                .newActivityInstance(eventKey)
                .setActivityId(workflowInstanceEvent.getActivityId())
                .setTaskKey(-1L)
                .write();

            if (!isNilPayload(sourcePayload))
            {
                payloadCache.addPayload(workflowInstanceEvent.getWorkflowInstanceKey(), eventPosition, sourcePayload);
            }
        }
    }

    private final class ActivityActivatedEventProcessor implements EventProcessor
    {
        @Override
        public void processEvent()
        {
            final ExecutableServiceTask serviceTask = getCurrentActivity();
            final TaskMetadata taskMetadata = serviceTask.getTaskMetadata();

            taskEvent
                .setEventType(TaskEventType.CREATE)
                .setType(taskMetadata.getTaskType())
                .setRetries(taskMetadata.getRetries())
                .setPayload(workflowInstanceEvent.getPayload());

            setTaskHeaders(serviceTask, taskMetadata);
        }

        private void setTaskHeaders(ExecutableServiceTask serviceTask, TaskMetadata taskMetadata)
        {
            final TaskHeaders taskHeaders = taskEvent.headers()
                .setBpmnProcessId(workflowInstanceEvent.getBpmnProcessId())
                .setWorkflowDefinitionVersion(workflowInstanceEvent.getVersion())
                .setWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                .setWorkflowKey(workflowInstanceEvent.getWorkflowKey())
                .setActivityId(serviceTask.getId())
                .setActivityInstanceKey(eventKey);

            final TaskHeader[] customHeaders = taskMetadata.getHeaders();
            for (int i = 0; i < customHeaders.length; i++)
            {
                final TaskHeader customHeader = customHeaders[i];

                taskHeaders.customHeaders().add()
                    .setKey(customHeader.getKey())
                    .setValue(customHeader.getValue());
            }
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeTaskEvent(writer.positionAsKey());
        }
    }

    private final class TaskCreatedProcessor implements EventProcessor
    {
        private boolean isActive;

        // TODO event is ignored by recovery

        @Override
        public void processEvent()
        {
            isActive = false;

            final TaskHeaders taskHeaders = taskEvent.headers();
            final long activityInstanceKey = taskHeaders.getActivityInstanceKey();
            if (activityInstanceKey > 0)
            {
                final long currentActivityInstanceKey = workflowInstanceIndex.wrapWorkflowInstanceKey(taskHeaders.getWorkflowInstanceKey()).getActivityInstanceKey();

                isActive = activityInstanceKey == currentActivityInstanceKey;
            }
        }

        @Override
        public void updateState()
        {
            if (isActive)
            {
                activityInstanceIndex
                    .wrapActivityInstanceKey(taskEvent.headers().getActivityInstanceKey())
                    .setTaskKey(eventKey)
                    .write();
            }
        }
    }

    private final class SequenceFlowTakenEventProcessor implements EventProcessor
    {
        @Override
        public void processEvent()
        {
            final ExecutableSequenceFlow sequenceFlow = getCurrentActivity();
            final ExecutableFlowNode targetNode = sequenceFlow.getTargetNode();

            workflowInstanceEvent.setActivityId(targetNode.getId());

            if (targetNode instanceof ExecutableEndEvent)
            {
                workflowInstanceEvent.setEventType(WorkflowInstanceEventType.END_EVENT_OCCURRED);
            }
            else if (targetNode instanceof ExecutableServiceTask)
            {
                workflowInstanceEvent.setEventType(WorkflowInstanceEventType.ACTIVITY_READY);
            }
            else
            {
                throw new RuntimeException("Currently not supported. A sequence flow must end in an end event or service task.");
            }
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.positionAsKey());
        }
    }

    private final class TakeSequenceFlowAspectHandler implements EventProcessor
    {
        @Override
        public void processEvent()
        {
            final ExecutableFlowNode currentActivity = getCurrentActivity();

            // the activity has exactly one outgoing sequence flow
            final ExecutableSequenceFlow sequenceFlow = currentActivity.getOutgoingSequenceFlows()[0];

            workflowInstanceEvent
                .setEventType(WorkflowInstanceEventType.SEQUENCE_FLOW_TAKEN)
                .setActivityId(sequenceFlow.getId());
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.positionAsKey());
        }
    }

    private final class ConsumeTokenAspectHandler implements EventProcessor
    {
        private boolean isCompleted;
        private int activeTokenCount;

        @Override
        public void processEvent()
        {
            isCompleted = false;

            activeTokenCount = workflowInstanceIndex
                    .wrapWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                    .getTokenCount();
            if (activeTokenCount == 1)
            {
                workflowInstanceEvent
                    .setEventType(WorkflowInstanceEventType.WORKFLOW_INSTANCE_COMPLETED)
                    .setActivityId("");

                isCompleted = true;
            }
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            long position = 0L;

            if (isCompleted)
            {
                position = writeWorkflowEvent(
                        writer.key(workflowInstanceEvent.getWorkflowInstanceKey()));
            }
            return position;
        }

        @Override
        public void updateState()
        {
            if (isCompleted)
            {
                workflowInstanceIndex.remove(workflowInstanceEvent.getWorkflowInstanceKey());
                payloadCache.remove(workflowInstanceEvent.getWorkflowInstanceKey());
            }
            else
            {
                workflowInstanceIndex
                    .setActiveTokenCount(activeTokenCount - 1)
                    .write();
            }
        }
    }

    private final class DeployedWorkflowEventProcessor implements EventProcessor
    {
        protected final UnsafeBuffer writeBuffer = new UnsafeBuffer(new byte[BpmnTransformer.ID_MAX_LENGTH]);

        // TODO event is ignored by recovery

        @Override
        public void processEvent()
        {
            // deployment
            final ArrayValueIterator<DeployedWorkflow> deployedWorkflowArrayValueIterator = deploymentEvent.deployedWorkflows();

            while (deployedWorkflowArrayValueIterator.hasNext())
            {
                final DeployedWorkflow deployedWorkflow = deployedWorkflowArrayValueIterator.next();

                final DirectBuffer bpmnProcessId = deployedWorkflow.getBpmnProcessId();
                bpmnProcessId.getBytes(0, writeBuffer, 0, bpmnProcessId.capacity());

                final int version = deployedWorkflow.getVersion();

                latestWorkflowVersionIndex.put(writeBuffer.byteArray(), version);

                workflowDeploymentCache.addDeployedWorkflow(bpmnProcessId, version, eventPosition);
            }
        }
    }

    private final class TaskCompletedEventProcessor implements EventProcessor
    {
        private boolean isActivityCompleted;
        private long activityInstanceKey;

        @Override
        public void processEvent()
        {
            isActivityCompleted = false;

            final TaskHeaders taskHeaders = taskEvent.headers();
            activityInstanceKey = taskHeaders.getActivityInstanceKey();

            if (taskHeaders.getWorkflowInstanceKey() > 0 && isTaskOpen(activityInstanceKey))
            {
                workflowInstanceEvent
                    .setEventType(WorkflowInstanceEventType.ACTIVITY_COMPLETING)
                    .setBpmnProcessId(taskHeaders.getBpmnProcessId())
                    .setVersion(taskHeaders.getWorkflowDefinitionVersion())
                    .setWorkflowKey(taskHeaders.getWorkflowKey())
                    .setWorkflowInstanceKey(taskHeaders.getWorkflowInstanceKey())
                    .setActivityId(taskHeaders.getActivityId())
                    .setPayload(taskEvent.getPayload());

                isActivityCompleted = true;
            }
        }

        private boolean isTaskOpen(long activityInstanceKey)
        {
            // task key = -1 when activity is left
            return activityInstanceIndex.wrapActivityInstanceKey(activityInstanceKey).getTaskKey() == eventKey;
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return isActivityCompleted ? writeWorkflowEvent(writer.key(activityInstanceKey)) : 0L;
        }

        @Override
        public void updateState()
        {
            if (isActivityCompleted)
            {
                activityInstanceIndex
                    .setTaskKey(-1L)
                    .write();
            }
        }
    }

    private final class ActivityCompletingEventProcessor implements EventProcessor
    {
        public static final String INCIDENT_ERROR_MSG_MISSING_TASK_PAYLOAD_ON_OUT_MAPPING = "Task was completed without an payload - processing of output mapping failed!";

        @Override
        public void processEvent()
        {
            final ExecutableServiceTask serviceTask = getCurrentActivity();

            workflowInstanceEvent.setEventType(WorkflowInstanceEventType.ACTIVITY_COMPLETED);

            setWorkflowInstancePayload(serviceTask.getIoMapping().getOutputMappings());
        }

        private void setWorkflowInstancePayload(Mapping[] mappings)
        {
            final DirectBuffer workflowInstancePayload = payloadCache.getPayload(workflowInstanceEvent.getWorkflowInstanceKey());
            final DirectBuffer taskPayload = workflowInstanceEvent.getPayload();
            final boolean isNilPayload = isNilPayload(taskPayload);
            if (mappings.length > 0)
            {
                if (isNilPayload)
                {
                    throw new MappingException(INCIDENT_ERROR_MSG_MISSING_TASK_PAYLOAD_ON_OUT_MAPPING);
                }
                final int resultLen = payloadMappingProcessor.merge(taskPayload, workflowInstancePayload, mappings);
                final MutableDirectBuffer buffer = payloadMappingProcessor.getResultBuffer();
                workflowInstanceEvent.setPayload(buffer, 0, resultLen);
            }
            else if (isNilPayload)
            {
                // no payload from task complete
                workflowInstanceEvent.setPayload(workflowInstancePayload, 0, workflowInstancePayload.capacity());
            }
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.key(eventKey));
        }

        @Override
        public void updateState()
        {
            workflowInstanceIndex
                .wrapWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                .setActivityKey(-1L)
                .write();

            activityInstanceIndex.remove(eventKey);
        }
    }

    private final class CancelWorkflowInstanceProcessor implements EventProcessor
    {
        private final WorkflowInstanceEvent activityInstanceEvent = new WorkflowInstanceEvent();

        private boolean isCanceled;
        private long activityInstanceKey;
        private long taskKey;

        @Override
        public void processEvent()
        {
            isCanceled = false;

            workflowInstanceIndex.wrapWorkflowInstanceKey(eventKey);

            if (workflowInstanceIndex.getTokenCount() > 0)
            {
                lookupWorkflowInstanceEvent(workflowInstanceIndex.getPosition());

                workflowInstanceEvent
                    .setEventType(WorkflowInstanceEventType.WORKFLOW_INSTANCE_CANCELED)
                    .setPayload(WorkflowInstanceEvent.NO_PAYLOAD);

                activityInstanceKey = workflowInstanceIndex.getActivityInstanceKey();
                taskKey = activityInstanceIndex.wrapActivityInstanceKey(activityInstanceKey).getTaskKey();

                isCanceled = true;
            }
            else
            {
                workflowInstanceEvent.setEventType(WorkflowInstanceEventType.CANCEL_WORKFLOW_INSTANCE_REJECTED);
            }
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            logStreamBatchWriter
                .producerId(streamProcessorId)
                .sourceEvent(logStreamTopicName, logStreamPartitionId, eventPosition);

            if (taskKey > 0)
            {
                writeCancelTaskEvent(logStreamBatchWriter.event(), taskKey);
            }

            if (activityInstanceKey > 0)
            {
                writeTerminateActivityInstanceEvent(logStreamBatchWriter.event(), activityInstanceKey);
            }

            writeWorklowInstanceEvent(logStreamBatchWriter.event());

            return logStreamBatchWriter.tryWrite();
        }

        private void writeWorklowInstanceEvent(LogEntryBuilder logEntryBuilder)
        {
            targetEventMetadata.reset();
            targetEventMetadata
                    .protocolVersion(Constants.PROTOCOL_VERSION)
                    .raftTermId(targetStream.getTerm())
                    .eventType(WORKFLOW_EVENT);

            logEntryBuilder
                .key(eventKey)
                .metadataWriter(targetEventMetadata)
                .valueWriter(workflowInstanceEvent)
                .done();
        }

        private void writeCancelTaskEvent(LogEntryBuilder logEntryBuilder, long taskKey)
        {
            targetEventMetadata.reset();
            targetEventMetadata
                .protocolVersion(Constants.PROTOCOL_VERSION)
                .raftTermId(targetStream.getTerm())
                .eventType(TASK_EVENT);

            taskEvent.reset();
            taskEvent
                .setEventType(TaskEventType.CANCEL)
                .setType(EMPTY_TASK_TYPE)
                .headers()
                    .setBpmnProcessId(workflowInstanceEvent.getBpmnProcessId())
                    .setWorkflowDefinitionVersion(workflowInstanceEvent.getVersion())
                    .setWorkflowInstanceKey(eventKey)
                    .setActivityId(activityInstanceIndex.getActivityId())
                    .setActivityInstanceKey(activityInstanceKey);

            logEntryBuilder
                .key(taskKey)
                .metadataWriter(targetEventMetadata)
                .valueWriter(taskEvent)
                .done();
        }

        private void writeTerminateActivityInstanceEvent(LogEntryBuilder logEntryBuilder, long activityInstanceKey)
        {
            targetEventMetadata.reset();
            targetEventMetadata
                    .protocolVersion(Constants.PROTOCOL_VERSION)
                    .raftTermId(targetStream.getTerm())
                    .eventType(WORKFLOW_EVENT);

            activityInstanceEvent.reset();
            activityInstanceEvent
                .setEventType(WorkflowInstanceEventType.ACTIVITY_TERMINATED)
                .setBpmnProcessId(workflowInstanceEvent.getBpmnProcessId())
                .setVersion(workflowInstanceEvent.getVersion())
                .setWorkflowInstanceKey(eventKey)
                .setWorkflowKey(workflowInstanceEvent.getWorkflowKey())
                .setActivityId(activityInstanceIndex.getActivityId());

            logEntryBuilder
                .key(activityInstanceKey)
                .metadataWriter(targetEventMetadata)
                .valueWriter(activityInstanceEvent)
                .done();
        }

        @Override
        public boolean executeSideEffects()
        {
            return sendWorkflowInstanceResponse();
        }

        @Override
        public void updateState()
        {
            if (isCanceled)
            {
                workflowInstanceIndex.remove(eventKey);
                payloadCache.remove(eventKey);
                activityInstanceIndex.remove(activityInstanceKey);
            }
        }
    }

    private final class ActiveWorkflowInstanceProcessor implements EventProcessor
    {
        private final EventProcessor processor;

        private boolean isActive;

        ActiveWorkflowInstanceProcessor(EventProcessor processor)
        {
            this.processor = processor;
        }

        @Override
        public void processEvent()
        {
            isActive = workflowInstanceIndex
                    .wrapWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                    .getTokenCount() > 0;

            if (isActive)
            {
                processor.processEvent();
            }
        }

        @Override
        public boolean executeSideEffects()
        {
            return isActive ? processor.executeSideEffects() : true;
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return isActive ? processor.writeEvent(writer) : 0L;
        }

        @Override
        public void updateState()
        {
            if (isActive)
            {
                processor.updateState();
            }
        }
    }

    private final class UpdatePayloadProcessor implements EventProcessor
    {
        private boolean isUpdated;

        @Override
        public void processEvent()
        {
            isUpdated = false;

            final long currentActivityInstanceKey = workflowInstanceIndex.wrapWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey()).getActivityInstanceKey();

            // the index contains the activity when it is ready, activated or completing
            // in this cases, the payload can be updated and it is taken for the next workflow instance event
            WorkflowInstanceEventType workflowInstanceEventType = WorkflowInstanceEventType.UPDATE_PAYLOAD_REJECTED;
            if (currentActivityInstanceKey > 0 && currentActivityInstanceKey == eventKey)
            {
                final DirectBuffer payload = workflowInstanceEvent.getPayload();
                if (isValidPayload(payload))
                {
                    workflowInstanceEventType = WorkflowInstanceEventType.PAYLOAD_UPDATED;
                    isUpdated = true;
                }
            }
            workflowInstanceEvent.setEventType(workflowInstanceEventType);
        }

        @Override
        public boolean executeSideEffects()
        {
            return sendWorkflowInstanceResponse();
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            return writeWorkflowEvent(writer.key(eventKey));
        }

        @Override
        public void updateState()
        {
            if (isUpdated)
            {
                payloadCache.addPayload(workflowInstanceEvent.getWorkflowInstanceKey(), eventPosition, workflowInstanceEvent.getPayload());
            }
        }
    }
}
