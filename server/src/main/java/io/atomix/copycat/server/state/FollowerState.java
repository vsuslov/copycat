/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.state;

import io.atomix.catalyst.concurrent.Scheduled;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.protocol.*;
import io.atomix.copycat.server.storage.entry.Entry;
import io.atomix.copycat.server.util.Quorum;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Follower state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class FollowerState extends ActiveState {
  private final FollowerAppender appender;
  private final Random random = new Random();
  private Scheduled heartbeatTimer;

  public FollowerState(ServerContext context) {
    super(context);
    this.appender = new FollowerAppender(context);
  }

  @Override
  public CopycatServer.State type() {
    return CopycatServer.State.FOLLOWER;
  }

  @Override
  public synchronized CompletableFuture<ServerState> open() {
    return super.open().thenRun(this::startHeartbeatTimeout).thenApply(v -> this);
  }

  /**
   * Starts the heartbeat timer.
   */
  private void startHeartbeatTimeout() {
    LOGGER.trace("{} - Starting heartbeat timer", context.getCluster().member().address());
    resetHeartbeatTimeout();
  }

  /**
   * Resets the heartbeat timer.
   */
  private void resetHeartbeatTimeout() {
    context.checkThread();
    if (isClosed())
      return;

    // If a timer is already set, cancel the timer.
    if (heartbeatTimer != null) {
      heartbeatTimer.cancel();
    }

    // Set the election timeout in a semi-random fashion with the random range
    // being election timeout and 2 * election timeout.
    Duration delay = context.getElectionTimeout().plus(Duration.ofMillis(random.nextInt((int) context.getElectionTimeout().toMillis())));
    heartbeatTimer = context.getThreadContext().schedule(delay, () -> {
      heartbeatTimer = null;
      if (isOpen()) {
        context.setLeader(0);
        LOGGER.debug("{} - Heartbeat timed out in {}", context.getCluster().member().address(), delay);
        sendPollRequests();
      }
    });
  }

  /**
   * Polls all members of the cluster to determine whether this member should transition to the CANDIDATE state.
   */
  private void sendPollRequests() {
    // Set a new timer within which other nodes must respond in order for this node to transition to candidate.
    heartbeatTimer = context.getThreadContext().schedule(context.getElectionTimeout(), () -> {
      LOGGER.debug("{} - Failed to poll a majority of the cluster in {}", context.getCluster().member().address(), context.getElectionTimeout());
      resetHeartbeatTimeout();
    });

    // Create a quorum that will track the number of nodes that have responded to the poll request.
    final AtomicBoolean complete = new AtomicBoolean();
    final Set<ServerMember> votingMembers = new HashSet<>(context.getClusterState().getActiveMemberStates().stream().map(MemberState::getMember).collect(Collectors.toList()));

    // If there are no other members in the cluster, immediately transition to leader.
    if (votingMembers.isEmpty()) {
      context.transition(CopycatServer.State.CANDIDATE);
      return;
    }

    final Quorum quorum = new Quorum(context.getClusterState().getQuorum(), (elected) -> {
      // If a majority of the cluster indicated they would vote for us then transition to candidate.
      complete.set(true);
      if (elected) {
        context.transition(CopycatServer.State.CANDIDATE);
      } else {
        resetHeartbeatTimeout();
      }
    });

    // First, load the last log entry to get its term. We load the entry
    // by its index since the index is required by the protocol.
    long lastIndex = context.getLog().lastIndex();
    Entry lastEntry = lastIndex > 0 ? context.getLog().get(lastIndex) : null;

    final long lastTerm;
    if (lastEntry != null) {
      lastTerm = lastEntry.getTerm();
      lastEntry.close();
    } else {
      lastTerm = 0;
    }

    LOGGER.info("{} - Polling members {}", context.getCluster().member().address(), votingMembers);

    // Once we got the last log term, iterate through each current member
    // of the cluster and vote each member for a vote.
    for (ServerMember member : votingMembers) {
      LOGGER.trace("{} - Polling {} for next term {}", context.getCluster().member().address(), member, context.getTerm() + 1);
      PollRequest request = PollRequest.builder()
        .withTerm(context.getTerm())
        .withCandidate(context.getCluster().member().id())
        .withLogIndex(lastIndex)
        .withLogTerm(lastTerm)
        .build();
      context.getConnections().getConnection(member.serverAddress()).thenAccept(connection -> {
        connection.<PollRequest, PollResponse>sendAndReceive(PollRequest.NAME, request).whenCompleteAsync((response, error) -> {
          context.checkThread();
          if (isOpen() && !complete.get()) {
            if (error != null) {
              LOGGER.warn("{} - {}", context.getCluster().member().address(), error.getMessage());
              quorum.fail();
            } else {
              if (response.term() > context.getTerm()) {
                context.setTerm(response.term());
              }

              if (!response.accepted()) {
                LOGGER.trace("{} - Received rejected poll from {}", context.getCluster().member().address(), member);
                quorum.fail();
              } else if (response.term() != context.getTerm()) {
                LOGGER.trace("{} - Received accepted poll for a different term from {}", context.getCluster().member().address(), member);
                quorum.fail();
              } else {
                LOGGER.trace("{} - Received accepted poll from {}", context.getCluster().member().address(), member);
                quorum.succeed();
              }
            }
          }
        }, context.getThreadContext());
      });
    }
  }

  @Override
  public CompletableFuture<InstallResponse> install(InstallRequest request) {
    CompletableFuture<InstallResponse> future = super.install(request);
    resetHeartbeatTimeout();
    return future;
  }

  @Override
  public CompletableFuture<ConfigureResponse> configure(ConfigureRequest request) {
    CompletableFuture<ConfigureResponse> future = super.configure(request);
    resetHeartbeatTimeout();
    return future;
  }

  @Override
  public CompletableFuture<AppendResponse> append(AppendRequest request) {
    CompletableFuture<AppendResponse> future = super.append(request);

    // Reset the heartbeat timeout.
    resetHeartbeatTimeout();

    // Send AppendEntries requests to passive members if necessary.
    appender.appendEntries();
    return future;
  }

  @Override
  protected VoteResponse handleVote(VoteRequest request) {
    // Reset the heartbeat timeout if we voted for another candidate.
    VoteResponse response = super.handleVote(request);
    if (response.voted()) {
      resetHeartbeatTimeout();
    }
    return response;
  }

  /**
   * Cancels the heartbeat timeout.
   */
  private void cancelHeartbeatTimeout() {
    if (heartbeatTimer != null) {
      LOGGER.trace("{} - Cancelling heartbeat timer", context.getCluster().member().address());
      heartbeatTimer.cancel();
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close().thenRun(appender::close).thenRun(this::cancelHeartbeatTimeout);
  }

}
