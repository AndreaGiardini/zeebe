package org.camunda.tngp.broker.clustering.raft.state;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.camunda.tngp.broker.clustering.raft.Member;
import org.camunda.tngp.broker.clustering.raft.RaftContext;
import org.camunda.tngp.broker.clustering.raft.message.AppendRequest;
import org.camunda.tngp.broker.clustering.raft.message.AppendResponse;
import org.camunda.tngp.broker.clustering.raft.message.JoinRequest;
import org.camunda.tngp.broker.clustering.raft.message.JoinResponse;
import org.camunda.tngp.broker.clustering.raft.message.LeaveRequest;
import org.camunda.tngp.broker.clustering.raft.message.LeaveResponse;
import org.camunda.tngp.broker.clustering.raft.message.PollRequest;
import org.camunda.tngp.broker.clustering.raft.message.PollResponse;
import org.camunda.tngp.broker.clustering.raft.message.VoteRequest;
import org.camunda.tngp.broker.clustering.raft.message.VoteResponse;
import org.camunda.tngp.clustering.gossip.RaftMembershipState;
import org.camunda.tngp.logstreams.log.LogStream;
import org.camunda.tngp.logstreams.log.LoggedEvent;
import org.camunda.tngp.transport.SocketAddress;

public abstract class ActiveState extends InactiveState
{
    private final Random random = new Random();

    public ActiveState(final RaftContext context)
    {
        super(context);
    }

    protected long randomTimeout(final long min)
    {
        return min + (Math.abs(random.nextLong()) % min);
    }

    public VoteResponse vote(final VoteRequest voteRequest)
    {
        final int voteTerm = voteRequest.term();

        final boolean transition = updateTermAndLeader(voteTerm, null);

        final VoteResponse voteResponse = handleVoteRequest(voteRequest);

        if (transition)
        {
            raft.transition(RaftMembershipState.FOLLOWER);
        }

        return voteResponse;
    }

    protected VoteResponse handleVoteRequest(final VoteRequest voteRequest)
    {
        final int currentTerm = raft.term();
        final SocketAddress currentLeader = raft.leader();
        final SocketAddress lastVotedFor = raft.lastVotedFor();

        final int voteTerm = voteRequest.term();
        final long lastEntryPosition = voteRequest.lastEntryPosition();
        final int lastEntryTerm = voteRequest.lastEntryTerm();
        final Member candidate = voteRequest.candidate();

        boolean granted = false;

        if (voteTerm < currentTerm)
        {
            granted = false;
        }
        else if (currentLeader != null)
        {
            granted = false;
        }
        else if (!context.getRaft().members().contains(candidate))
        {
            granted = false;
        }
        else if (lastVotedFor == null)
        {
            granted = isLogUpToDate(lastEntryPosition, lastEntryTerm);
        }
        else
        {
            granted = lastVotedFor.equals(candidate.endpoint());
        }

        if (granted)
        {
            raft.lastVotedFor(candidate.endpoint());
        }

        voteResponse.reset();
        return voteResponse
                .term(currentTerm)
                .granted(granted);
    }

    public PollResponse poll(final PollRequest pollRequest)
    {
        final int pollTerm = pollRequest.term();

        final boolean transition = updateTermAndLeader(pollTerm, null);

        final PollResponse pollResponse = handlePollRequest(pollRequest);

        if (transition)
        {
            raft.transition(RaftMembershipState.FOLLOWER);
        }

        return pollResponse;
    }

    protected PollResponse handlePollRequest(final PollRequest pollRequest)
    {
        final int currentTerm = raft.term();

        final int voteTerm = pollRequest.term();
        final long lastEntryPosition = pollRequest.lastEntryPosition();
        final int lastEntryTerm = pollRequest.lastEntryTerm();

        boolean granted = false;

        if (voteTerm < currentTerm)
        {
            granted = false;
        }
        else
        {
            granted = isLogUpToDate(lastEntryPosition, lastEntryTerm);
        }

        pollResponse.reset();
        return pollResponse
                .term(currentTerm)
                .granted(granted);
    }

    protected boolean isLogUpToDate(final long entryPosition, final int entryTerm)
    {
        return logStreamState.isLastReceivedEntry(entryPosition, entryTerm);
    }

    public AppendResponse append(final AppendRequest appendRequest)
    {
        final int term = appendRequest.term();
        final Member leader = appendRequest.leader();

        final boolean transition = updateTermAndLeader(term, leader);

        final AppendResponse response = handleAppendRequest(appendRequest);

        if (transition)
        {
            raft.transition(RaftMembershipState.FOLLOWER);
        }

        return response;
    }

    protected AppendResponse handleAppendRequest(final AppendRequest request)
    {
        final long currentTerm = raft.term();
        final long appendTerm = request.term();

        if (appendTerm >= currentTerm)
        {
            return appendEntry(request);
        }
        else
        {
            final long position = request.previousEntryPosition();
            return rejectAppendRequest(position);
        }
    }

    protected AppendResponse appendEntry(final AppendRequest request)
    {
        final long requestPreviousEntryPosition = request.previousEntryPosition();
        final int requestPreviousEntryTerm = request.previousEntryTerm();
        final LoggedEvent entry = request.entry();

        if (!logStreamState.isLastReceivedEntry(requestPreviousEntryPosition, requestPreviousEntryTerm))
        {
            logStreamState.discardBufferedEntries();

            if (!logStreamState.isLastWrittenEntry(requestPreviousEntryPosition, requestPreviousEntryTerm))
            {
                if (logStreamState.lastWrittenPosition() > requestPreviousEntryPosition)
                {

                    if (logStreamState.containsEntry(requestPreviousEntryPosition, requestPreviousEntryTerm))
                    {
                        // TODO: (1) truncate only if not (locally) committed (2) truncate only if request.entry() not null
                        logStreamState.setLastWrittenEntry(requestPreviousEntryPosition, requestPreviousEntryTerm);
                    }
                    else
                    {
                        return rejectAppendRequest(requestPreviousEntryPosition);
                    }
                }
                else if (logStreamState.lastWrittenPosition() < requestPreviousEntryPosition)
                {
                    return rejectAppendRequest(logStreamState.lastWrittenPosition() + 1);
                }
                else
                {
                    return rejectAppendRequest(logStreamState.lastWrittenPosition());
                }
            }
        }

        if (entry != null)
        {
            logStreamState.append(entry);
        }

        final long requestCommitPosition = request.commitPosition();
        raft.commitPosition(requestCommitPosition);

        if (logStreamState.shouldFlushBufferedEntries())
        {
            logStreamState.flushBufferedEntries();
        }

        return acknowledgeAppendRequest(logStreamState.lastWrittenPosition());
    }

    @Override
    public void appended(AppendResponse appendResponse)
    {
        // ignore;
    }

    @Override
    public CompletableFuture<JoinResponse> join(JoinRequest joinRequest)
    {
        joinResponse.reset();
        return CompletableFuture.completedFuture(
            joinResponse
                .term(raft.term())
                .succeeded(false)
                .members(raft.members())
        );
    }

    @Override
    public CompletableFuture<LeaveResponse> leave(LeaveRequest leaveRequest)
    {
        leaveResponse.reset();
        return CompletableFuture.completedFuture(
            leaveResponse
                .term(raft.term())
                .succeeded(false)
                .members(raft.members())
        );
    }

    protected AppendResponse rejectAppendRequest(final long logPosition)
    {
        return buildAppendResponse(logPosition, false);
    }

    protected AppendResponse acknowledgeAppendRequest(final long logPosition)
    {
        return buildAppendResponse(logPosition, true);
    }

    protected AppendResponse buildAppendResponse(final long logPosition, final boolean succeeded)
    {
        appendResponse.reset();

        final int term = raft.term();
        final LogStream logStream = raft.logStream();

        return appendResponse
                .topicName(logStream.getTopicName())
                .partitionId(logStream.getPartitionId())
                .term(term)
                .succeeded(succeeded)
                .entryPosition(logPosition)
                .member(raft.member());

    }
}
