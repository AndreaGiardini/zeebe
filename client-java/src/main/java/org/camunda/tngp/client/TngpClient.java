package org.camunda.tngp.client;

import java.util.Properties;

import org.camunda.tngp.client.impl.TngpClientImpl;
import org.camunda.tngp.transport.requestresponse.client.TransportConnection;


public interface TngpClient extends AutoCloseable
{
    /**
     * Provides APIs specific to topics of type <code>task</code>.
     *
     * @param topicName
     *              the name of the topic
     *
     * @param partitionId
     *            the id of the topic partition
     */
    TaskTopicClient taskTopic(String topicName, int partitionId);

    /**
     * Provides APIs specific to topics of type <code>workflow</code>.
     *
     * @param topicName
     *              the name of the topic
     *
     * @param partitionId
     *            the id of the topic partition
     */
    WorkflowTopicClient workflowTopic(String topicName, int partitionId);

    /**
     * Provides general purpose APIs for any kind of topic.
     *
     * @param topicName
     *              the name of the topic
     *
     * @param partitionId
     *            the id of the topic partition
     */
    TopicClient topic(String topicName, int partitionId);

    /**
     * Provides access to management operations such as those for creating and dropping topics.
     *
     * @return the management client
     */
    ManagementClient management();

    /**
     * Open {@link TransportConnection} for request / response style communication.
     */
    TransportConnection openConnection();

    @Override
    void close();

    static TngpClient create(Properties properties)
    {
        return new TngpClientImpl(properties);
    }
}
