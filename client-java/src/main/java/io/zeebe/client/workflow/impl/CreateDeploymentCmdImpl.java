package io.zeebe.client.workflow.impl;

import static io.zeebe.util.EnsureUtil.*;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import io.zeebe.client.impl.ClientCommandManager;
import io.zeebe.client.impl.Topic;
import io.zeebe.client.impl.cmd.AbstractExecuteCmdImpl;
import io.zeebe.client.impl.data.MsgPackConverter;
import io.zeebe.client.workflow.cmd.CreateDeploymentCmd;
import io.zeebe.client.workflow.cmd.DeploymentResult;
import io.zeebe.client.workflow.cmd.WorkflowDefinition;
import io.zeebe.protocol.clientapi.EventType;
import io.zeebe.util.StreamUtil;

public class CreateDeploymentCmdImpl extends AbstractExecuteCmdImpl<DeploymentEvent, DeploymentResult> implements CreateDeploymentCmd
{
    protected final DeploymentEvent deploymentEvent = new DeploymentEvent();

    protected String resource;

    public CreateDeploymentCmdImpl(final ClientCommandManager commandManager, final ObjectMapper objectMapper, MsgPackConverter msgPackConverter, final Topic topic)
    {
        super(commandManager, objectMapper, topic, DeploymentEvent.class, EventType.DEPLOYMENT_EVENT);
    }

    @Override
    public CreateDeploymentCmd resourceString(final String resource)
    {
        this.resource = resource;
        return this;
    }

    @Override
    public CreateDeploymentCmd resourceStream(final InputStream resourceStream)
    {
        ensureNotNull("resource stream", resourceStream);

        try
        {
            final byte[] bytes = StreamUtil.read(resourceStream);

            return resourceString(new String(bytes, CHARSET));
        }
        catch (final IOException e)
        {
            final String exceptionMsg = String.format("Cannot deploy bpmn resource from stream. %s", e.getMessage());
            throw new RuntimeException(exceptionMsg, e);
        }
    }

    @Override
    public CreateDeploymentCmd resourceFromClasspath(final String resourceName)
    {
        ensureNotNull("classpath resource", resourceName);

        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourceName))
        {
            if (resourceStream != null)
            {
                return resourceStream(resourceStream);
            }
            else
            {
                throw new FileNotFoundException(resourceName);
            }

        }
        catch (final IOException e)
        {
            final String exceptionMsg = String.format("Cannot deploy resource from classpath. %s", e.getMessage());
            throw new RuntimeException(exceptionMsg, e);
        }
    }

    @Override
    public CreateDeploymentCmd resourceFile(final String filename)
    {
        ensureNotNull("filename", filename);

        try (InputStream resourceStream = new FileInputStream(filename))
        {
            return resourceStream(resourceStream);
        }
        catch (final IOException e)
        {
            final String exceptionMsg = String.format("Cannot deploy resource from file. %s", e.getMessage());
            throw new RuntimeException(exceptionMsg, e);
        }
    }

    @Override
    public CreateDeploymentCmd bpmnModelInstance(final BpmnModelInstance modelInstance)
    {
        ensureNotNull("model instance", modelInstance);

        final String modelInstanceAsString = Bpmn.convertToString(modelInstance);

        return resourceString(modelInstanceAsString);
    }

    @Override
    public void validate()
    {
        super.validate();
        ensureNotNull("resource", resource);
    }

    @Override
    protected Object writeCommand()
    {
        deploymentEvent.setEventType(DeploymentEventType.CREATE_DEPLOYMENT);
        deploymentEvent.setBpmnXml(resource);

        return deploymentEvent;
    }

    @Override
    protected long getKey()
    {
        return -1L;
    }

    @Override
    protected void reset()
    {
        resource = null;

        deploymentEvent.reset();
    }

    @Override
    protected DeploymentResult getResponseValue(final long key, final DeploymentEvent event)
    {
        final boolean isDeployed = event.getEventType() == DeploymentEventType.DEPLOYMENT_CREATED;

        final DeploymentResultImpl result = new DeploymentResultImpl()
                .setIsDeployed(isDeployed)
                .setKey(key)
                .setErrorMessage(event.getErrorMessage());

        if (event.getDeployedWorkflows() != null)
        {
            final List<WorkflowDefinition> deployedWorkflows = event.getDeployedWorkflows().stream()
                    .map(wf -> new WorkflowDefinitionImpl()
                            .setBpmnProcessId(wf.getBpmnProcessId())
                            .setVersion(wf.getVersion()))
                    .collect(Collectors.toList());

            result.setDeployedWorkflows(deployedWorkflows);
        }

        return result;
    }

}
