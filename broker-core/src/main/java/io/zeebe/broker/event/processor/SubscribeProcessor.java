package io.zeebe.broker.event.processor;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.agrona.DirectBuffer;

import io.zeebe.broker.Constants;
import io.zeebe.broker.logstreams.BrokerEventMetadata;
import io.zeebe.logstreams.log.LogStreamWriter;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.processor.EventProcessor;

public class SubscribeProcessor implements EventProcessor
{
    protected final int maximumNameLength;
    protected final TopicSubscriptionManagementProcessor manager;

    protected LoggedEvent event;
    protected BrokerEventMetadata metadata;
    protected TopicSubscriberEvent subscriberEvent;

    protected EventProcessor state;
    protected final RequestFailureProcessor failedRequestState = new RequestFailureProcessor();
    protected final CreateSubscriptionServiceProcessor createProcessorState = new CreateSubscriptionServiceProcessor();
    protected final AwaitSubscriptionServiceProcessor awaitProcessorState = new AwaitSubscriptionServiceProcessor();
    protected final SubscriptionServiceSuccessProcessor successState = new SubscriptionServiceSuccessProcessor();

    public SubscribeProcessor(
            int maximumNameLength,
            TopicSubscriptionManagementProcessor manager)
    {
        this.maximumNameLength = maximumNameLength;
        this.manager = manager;
    }

    public void wrap(LoggedEvent event, BrokerEventMetadata metadata, TopicSubscriberEvent subscriberEvent)
    {
        this.event = event;
        this.metadata = metadata;
        this.subscriberEvent = subscriberEvent;
    }

    @Override
    public void processEvent()
    {
        final DirectBuffer subscriptionName = subscriberEvent.getName();

        if (subscriptionName.capacity() > maximumNameLength)
        {
            failedRequestState.wrapError("Cannot open topic subscription " + subscriberEvent.getNameAsString() +
                    ". Subscription name must be " + maximumNameLength + " characters or shorter.");
            state = failedRequestState;
            return;
        }
        else
        {
            state = createProcessorState;
        }
    }

    @Override
    public long writeEvent(LogStreamWriter writer)
    {
        return state.writeEvent(writer);
    }

    @Override
    public boolean executeSideEffects()
    {
        return state.executeSideEffects();
    }

    protected class RequestFailureProcessor implements EventProcessor
    {
        protected String error;

        public void wrapError(String error)
        {
            this.error = error;
        }

        @Override
        public void processEvent()
        {
        }

        @Override
        public boolean executeSideEffects()
        {
            return manager.writeRequestResponseError(metadata, event, error);
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            // in the future, we can write a SUBSCRIBE_FAILED event here,
            // but at the moment that would make no difference for the user
            return 0L;
        }

    }

    protected class CreateSubscriptionServiceProcessor implements EventProcessor
    {

        @Override
        public void processEvent()
        {
        }

        @Override
        public boolean executeSideEffects()
        {
            final DirectBuffer subscriptionName = subscriberEvent.getName();
            final long resumePosition = manager.determineResumePosition(
                    subscriptionName,
                    subscriberEvent.getStartPosition(),
                    subscriberEvent.getForceStart());

            final CompletableFuture<TopicSubscriptionPushProcessor> processorFuture = manager.openPushProcessorAsync(
                    metadata.getRequestStreamId(),
                    event.getKey(),
                    resumePosition,
                    subscriptionName,
                    subscriberEvent.getPrefetchCapacity());

            awaitProcessorState.wrap(processorFuture);
            state = awaitProcessorState;

            return false;
        }
    }

    protected class AwaitSubscriptionServiceProcessor implements EventProcessor
    {
        protected CompletableFuture<TopicSubscriptionPushProcessor> processorFuture;

        public void wrap(CompletableFuture<TopicSubscriptionPushProcessor> processorFuture)
        {
            this.processorFuture = processorFuture;
        }

        @Override
        public void processEvent()
        {
        }

        @Override
        public boolean executeSideEffects()
        {
            if (!processorFuture.isDone())
            {
                return false;
            }
            else
            {
                try
                {
                    final TopicSubscriptionPushProcessor processor = processorFuture.get();
                    successState.wrap(processor);
                    state = successState;

                    return false;
                }
                catch (CancellationException | ExecutionException e)
                {
                    final String errorMessage = e.getMessage();

                    failedRequestState.wrapError(errorMessage);
                    state = failedRequestState;

                    return false;
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException("Unexpected exception", e);
                }
            }
        }
    }

    protected class SubscriptionServiceSuccessProcessor implements EventProcessor
    {

        protected TopicSubscriptionPushProcessor processor;

        public void wrap(TopicSubscriptionPushProcessor processor)
        {
            this.processor = processor;
        }

        @Override
        public void processEvent()
        {
        }

        @Override
        public boolean executeSideEffects()
        {
            // success response is written on SUBSCRIBED event, as only then it is guaranteed that
            //   the start position is persisted

            manager.registerPushProcessor(processor);
            return true;
        }

        @Override
        public long writeEvent(LogStreamWriter writer)
        {
            metadata.protocolVersion(Constants.PROTOCOL_VERSION)
                .raftTermId(manager.getTargetStream().getTerm());

            subscriberEvent
                .setStartPosition(processor.getStartPosition())
                .setEventType(TopicSubscriberEventType.SUBSCRIBED);

            return writer
                .metadataWriter(metadata)
                .valueWriter(subscriberEvent)
                .key(event.getKey())
                .tryWrite();
        }
    }
}
