/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.broker.transport.controlmessage;

import java.util.Arrays;
import java.util.List;

import io.zeebe.broker.clustering.gossip.Gossip;
import io.zeebe.broker.clustering.handler.RequestTopologyHandler;
import io.zeebe.broker.event.handler.RemoveTopicSubscriptionHandler;
import io.zeebe.broker.event.processor.TopicSubscriptionService;
import io.zeebe.broker.task.TaskSubscriptionManager;
import io.zeebe.broker.transport.clientapi.ErrorResponseWriter;
import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.servicecontainer.ServiceStopContext;
import io.zeebe.util.actor.ActorScheduler;

public class ControlMessageHandlerManagerService implements Service<ControlMessageHandlerManager>
{
    protected final Injector<Dispatcher> sendBufferInjector = new Injector<>();
    protected final Injector<Dispatcher> controlMessageBufferInjector = new Injector<>();
    protected final Injector<ActorScheduler> actorSchedulerInjector = new Injector<>();
    protected final Injector<TaskSubscriptionManager> taskSubscriptionManagerInjector = new Injector<>();
    protected final Injector<TopicSubscriptionService> topicSubscriptionServiceInjector = new Injector<>();
    protected final Injector<Gossip> gossipInjector = new Injector<>();

    protected final long controlMessageRequestTimeoutInMillis;

    protected ControlMessageHandlerManager service;

    public ControlMessageHandlerManagerService(long controlMessageRequestTimeoutInMillis)
    {
        this.controlMessageRequestTimeoutInMillis = controlMessageRequestTimeoutInMillis;
    }

    @Override
    public void start(ServiceStartContext context)
    {
        final Dispatcher controlMessageBuffer = controlMessageBufferInjector.getValue();

        final Dispatcher sendBuffer = sendBufferInjector.getValue();
        final ControlMessageResponseWriter controlMessageResponseWriter = new ControlMessageResponseWriter(sendBuffer);
        final ErrorResponseWriter errorResponseWriter = new ErrorResponseWriter(sendBuffer);

        final ActorScheduler actorScheduler = actorSchedulerInjector.getValue();

        final TaskSubscriptionManager taskSubscriptionManager = taskSubscriptionManagerInjector.getValue();
        final TopicSubscriptionService topicSubscriptionService = topicSubscriptionServiceInjector.getValue();
        final Gossip gossip = gossipInjector.getValue();

        final List<ControlMessageHandler> controlMessageHandlers = Arrays.asList(
            new AddTaskSubscriptionHandler(taskSubscriptionManager, controlMessageResponseWriter, errorResponseWriter),
            new IncreaseTaskSubscriptionCreditsHandler(taskSubscriptionManager, controlMessageResponseWriter, errorResponseWriter),
            new RemoveTaskSubscriptionHandler(taskSubscriptionManager, controlMessageResponseWriter, errorResponseWriter),
            new RemoveTopicSubscriptionHandler(topicSubscriptionService, controlMessageResponseWriter, errorResponseWriter),
            new RequestTopologyHandler(gossip, controlMessageResponseWriter, errorResponseWriter)
        );

        service = new ControlMessageHandlerManager(controlMessageBuffer, errorResponseWriter, controlMessageRequestTimeoutInMillis, actorScheduler, controlMessageHandlers);

        context.async(service.openAsync());
    }

    @Override
    public void stop(ServiceStopContext context)
    {
        context.async(service.closeAsync());
    }

    @Override
    public ControlMessageHandlerManager get()
    {
        return service;
    }

    public Injector<Dispatcher> getSendBufferInjector()
    {
        return sendBufferInjector;
    }

    public Injector<Dispatcher> getControlMessageBufferInjector()
    {
        return controlMessageBufferInjector;
    }

    public Injector<ActorScheduler> getActorSchedulerInjector()
    {
        return actorSchedulerInjector;
    }

    public Injector<TaskSubscriptionManager> getTaskSubscriptionManagerInjector()
    {
        return taskSubscriptionManagerInjector;
    }

    public Injector<TopicSubscriptionService> getTopicSubscriptionServiceInjector()
    {
        return topicSubscriptionServiceInjector;
    }

    public Injector<Gossip> getGossipInjector()
    {
        return gossipInjector;
    }
}