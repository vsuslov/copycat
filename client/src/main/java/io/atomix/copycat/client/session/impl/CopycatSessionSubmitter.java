/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.client.session.impl;

import io.atomix.catalyst.concurrent.ThreadContext;
import io.atomix.catalyst.transport.TransportException;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.Command;
import io.atomix.copycat.NoOpCommand;
import io.atomix.copycat.Query;
import io.atomix.copycat.client.util.ClientConnection;
import io.atomix.copycat.error.CommandException;
import io.atomix.copycat.error.CopycatError;
import io.atomix.copycat.error.QueryException;
import io.atomix.copycat.error.UnknownSessionException;
import io.atomix.copycat.protocol.*;
import io.atomix.copycat.session.ClosedSessionException;
import io.atomix.copycat.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Session operation submitter.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
final class CopycatSessionSubmitter {
  private static final Logger LOG = LoggerFactory.getLogger(CopycatSessionSubmitter.class);
  private static final int[] FIBONACCI = new int[]{1, 1, 2, 3, 5};
  private static final Predicate<Throwable> EXCEPTION_PREDICATE = e ->
    e instanceof ConnectException
      || e instanceof TimeoutException
      || e instanceof TransportException
      || e instanceof ClosedChannelException;
  private static final Predicate<Throwable> CLOSED_PREDICATE = e ->
    e instanceof ClosedSessionException
      || e instanceof UnknownSessionException;
  private final ClientConnection connection;
  private final CopycatSessionState state;
  private final CopycatSessionSequencer sequencer;
  private final CopycatSessionManager manager;
  private final ThreadContext context;
  private final Map<Long, OperationAttempt> attempts = new LinkedHashMap<>();
  private final AtomicLong keepAliveIndex = new AtomicLong();

  public CopycatSessionSubmitter(ClientConnection connection, CopycatSessionState state, CopycatSessionSequencer sequencer, CopycatSessionManager manager, ThreadContext context) {
    this.connection = Assert.notNull(connection, "connection");
    this.state = Assert.notNull(state, "state");
    this.sequencer = Assert.notNull(sequencer, "sequencer");
    this.manager = Assert.notNull(manager, "manager");
    this.context = Assert.notNull(context, "context");
  }

  /**
   * Submits a command to the cluster.
   *
   * @param command The command to submit.
   * @param <T> The command result type.
   * @return A completable future to be completed once the command has been submitted.
   */
  public <T> CompletableFuture<T> submit(Command<T> command) {
    CompletableFuture<T> future = new CompletableFuture<>();
    context.execute(() -> submitCommand(command, future));
    return future;
  }

  /**
   * Submits a command to the cluster.
   */
  private <T> void submitCommand(Command<T> command, CompletableFuture<T> future) {
    CommandRequest request = CommandRequest.builder()
      .withSession(state.getSessionId())
      .withSequence(state.nextCommandRequest())
      .withCommand(command)
      .build();
    submitCommand(request, future);
  }

  /**
   * Submits a command request to the cluster.
   */
  private <T> void submitCommand(CommandRequest request, CompletableFuture<T> future) {
    submit(new CommandAttempt<>(sequencer.nextRequest(), request, future));
  }

  /**
   * Submits a query to the cluster.
   *
   * @param query The query to submit.
   * @param <T> The query result type.
   * @return A completable future to be completed once the query has been submitted.
   */
  public <T> CompletableFuture<T> submit(Query<T> query) {
    CompletableFuture<T> future = new CompletableFuture<>();
    context.execute(() -> submitQuery(query, future));
    return future;
  }

  /**
   * Submits a query to the cluster.
   */
  private <T> void submitQuery(Query<T> query, CompletableFuture<T> future) {
    QueryRequest request = QueryRequest.builder()
      .withSession(state.getSessionId())
      .withSequence(state.getCommandRequest())
      .withIndex(state.getResponseIndex())
      .withQuery(query)
      .build();
    submitQuery(request, future);
  }

  /**
   * Submits a query request to the cluster.
   */
  private <T> void submitQuery(QueryRequest request, CompletableFuture<T> future) {
    submit(new QueryAttempt<>(sequencer.nextRequest(), request, future));
  }

  /**
   * Submits an operation attempt.
   *
   * @param attempt The attempt to submit.
   */
  private <T extends OperationRequest, U extends OperationResponse, V> void submit(OperationAttempt<T, U, V> attempt) {
    if (!state.isOpen()) {
      attempt.fail(new ClosedSessionException("session closed"));
    } else {
      LOG.trace("{} - Sending {}", state.getSessionId(), attempt.request);
      attempts.put(attempt.sequence, attempt);
      connection.<T, U>sendAndReceive(attempt.type(), attempt.request).whenComplete(attempt);
      attempt.future.whenComplete((r, e) -> attempts.remove(attempt.sequence));
    }
  }

  /**
   * Resubmits commands starting after the given sequence number.
   * <p>
   * The sequence number from which to resend commands is the <em>request</em> sequence number,
   * not the client-side sequence number. We resend only commands since queries cannot be reliably
   * resent without losing linearizable semantics. Commands are resent by iterating through all pending
   * operation attempts and retrying commands where the sequence number is greater than the given
   * {@code commandSequence} number and the attempt number is less than or equal to the version.
   */
  private void resubmit(long commandSequence, OperationAttempt<?, ?, ?> attempt) {
    // If the client's response sequence number is greater than the given command sequence number,
    // the cluster likely has a new leader, and we need to reset the sequencing in the leader by
    // sending a keep-alive request.
    // Ensure that the client doesn't resubmit many concurrent KeepAliveRequests by tracking the last
    // keep-alive response sequence number and only resubmitting if the sequence number has changed.
    long responseSequence = state.getCommandResponse();
    if (commandSequence < responseSequence && keepAliveIndex.get() != responseSequence) {
      keepAliveIndex.set(responseSequence);
      manager.resetIndexes(state.getSessionId()).whenCompleteAsync((result, error) -> {
        if (error == null) {
          resubmit(responseSequence, attempt);
        } else {
          attempt.retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt.attempt-1, FIBONACCI.length-1)]));
        }
      }, context);
    } else {
      for (Map.Entry<Long, OperationAttempt> entry : attempts.entrySet()) {
        OperationAttempt operation = entry.getValue();
        if (operation instanceof CommandAttempt && operation.request.sequence() > commandSequence && operation.attempt <= attempt.attempt) {
          operation.retry();
        }
      }
    }
  }

  /**
   * Closes the submitter.
   *
   * @return A completable future to be completed with a list of pending operations.
   */
  public CompletableFuture<Void> close() {
    for (OperationAttempt attempt : new ArrayList<>(attempts.values())) {
      attempt.fail(new ClosedSessionException("session closed"));
    }
    attempts.clear();
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Operation attempt.
   */
  private abstract class OperationAttempt<T extends OperationRequest, U extends OperationResponse, V> implements BiConsumer<U, Throwable> {
    protected final long sequence;
    protected final int attempt;
    protected final T request;
    protected final CompletableFuture<V> future;

    protected OperationAttempt(long sequence, int attempt, T request, CompletableFuture<V> future) {
      this.sequence = sequence;
      this.attempt = attempt;
      this.request = request;
      this.future = future;
    }

    /**
     * Returns the operation type.
     *
     * @return The operation type.
     */
    protected abstract String type();

    /**
     * Returns the next instance of the attempt.
     *
     * @return The next instance of the attempt.
     */
    protected abstract OperationAttempt<T, U, V> next();

    /**
     * Returns a new instance of the default exception for the operation.
     *
     * @return A default exception for the operation.
     */
    protected abstract Throwable defaultException();

    /**
     * Completes the operation successfully.
     *
     * @param response The operation response.
     */
    protected abstract void complete(U response);

    /**
     * Completes the operation with an exception.
     *
     * @param error The completion exception.
     */
    protected void complete(Throwable error) {
      sequence(null, () -> future.completeExceptionally(error));
    }

    /**
     * Runs the given callback in proper sequence.
     *
     * @param response The operation response.
     * @param callback The callback to run in sequence.
     */
    protected final void sequence(OperationResponse response, Runnable callback) {
      sequencer.sequenceResponse(sequence, response, callback);
    }

    /**
     * Fails the attempt.
     */
    public void fail() {
      fail(defaultException());
    }

    /**
     * Fails the attempt with the given exception.
     *
     * @param t The exception with which to fail the attempt.
     */
    public void fail(Throwable t) {
      complete(t);
    }

    /**
     * Immediately retries the attempt.
     */
    public void retry() {
      context.execute(() -> submit(next()));
    }

    /**
     * Retries the attempt after the given duration.
     *
     * @param after The duration after which to retry the attempt.
     */
    public void retry(Duration after) {
      context.schedule(after, () -> submit(next()));
    }
  }

  /**
   * Command operation attempt.
   */
  private final class CommandAttempt<T> extends OperationAttempt<CommandRequest, CommandResponse, T> {

    public CommandAttempt(long sequence, CommandRequest request, CompletableFuture<T> future) {
      super(sequence, 1, request, future);
    }

    public CommandAttempt(long sequence, int attempt, CommandRequest request, CompletableFuture<T> future) {
      super(sequence, attempt, request, future);
    }

    @Override
    protected String type() {
      return CommandRequest.NAME;
    }

    @Override
    protected OperationAttempt<CommandRequest, CommandResponse, T> next() {
      return new CommandAttempt<>(sequence, this.attempt + 1, request, future);
    }

    @Override
    protected Throwable defaultException() {
      return new CommandException("failed to complete command");
    }

    @Override
    public void accept(CommandResponse response, Throwable error) {
      if (error == null) {
        LOG.trace("{} - Received {}", state.getSessionId(), response);
        if (response.status() == Response.Status.OK) {
          complete(response);
        }
        // COMMAND_ERROR indicates that the command was received by the leader out of sequential order.
        // We need to resend commands starting at the provided lastSequence number.
        else if (response.error() == CopycatError.Type.COMMAND_ERROR) {
          resubmit(response.lastSequence(), this);
        }
        // The following exceptions need to be handled at a higher level by the client or the user.
        else if (response.error() == CopycatError.Type.APPLICATION_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_CLIENT_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_SESSION_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_STATE_MACHINE_ERROR
          || response.error() == CopycatError.Type.INTERNAL_ERROR) {
          complete(response.error().createException());
        }
        // For all other errors, use fibonacci backoff to resubmit the command.
        else {
          retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt-1, FIBONACCI.length-1)]));
        }
      } else if (EXCEPTION_PREDICATE.test(error) || (error instanceof CompletionException && EXCEPTION_PREDICATE.test(error.getCause()))) {
        retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt-1, FIBONACCI.length-1)]));
      } else {
        fail(error);
      }
    }

    @Override
    public void fail(Throwable cause) {
      super.fail(cause);
      if (!CLOSED_PREDICATE.test(cause)) {
        CommandRequest request = CommandRequest.builder()
          .withSession(this.request.session())
          .withSequence(this.request.sequence())
          .withCommand(new NoOpCommand())
          .build();
        context.execute(() -> submit(new CommandAttempt<>(sequence, this.attempt + 1, request, future)));
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void complete(CommandResponse response) {
      sequence(response, () -> {
        state.setCommandResponse(request.sequence());
        state.setResponseIndex(response.index());
        future.complete((T) response.result());
      });
    }
  }

  /**
   * Query operation attempt.
   */
  private final class QueryAttempt<T> extends OperationAttempt<QueryRequest, QueryResponse, T> {
    public QueryAttempt(long sequence, QueryRequest request, CompletableFuture<T> future) {
      super(sequence, 1, request, future);
    }

    public QueryAttempt(long sequence, int attempt, QueryRequest request, CompletableFuture<T> future) {
      super(sequence, attempt, request, future);
    }

    @Override
    protected String type() {
      return QueryRequest.NAME;
    }

    @Override
    protected OperationAttempt<QueryRequest, QueryResponse, T> next() {
      return new QueryAttempt<>(sequence, this.attempt + 1, request, future);
    }

    @Override
    protected Throwable defaultException() {
      return new QueryException("failed to complete query");
    }

    @Override
    public void accept(QueryResponse response, Throwable error) {
      if (error == null) {
        LOG.trace("{} - Received {}", state.getSessionId(), response);
        if (response.status() == Response.Status.OK) {
          complete(response);
        } else {
          complete(response.error().createException());
        }
      } else {
        fail(error);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void complete(QueryResponse response) {
      sequence(response, () -> {
        state.setResponseIndex(response.index());
        future.complete((T) response.result());
      });
    }
  }

}
