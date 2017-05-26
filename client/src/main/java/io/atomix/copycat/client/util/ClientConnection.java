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
 * limitations under the License
 */
package io.atomix.copycat.client.util;

import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.transport.TransportException;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.client.session.impl.CopycatSessionState;
import io.atomix.copycat.error.CopycatError;
import io.atomix.copycat.protocol.ConnectRequest;
import io.atomix.copycat.protocol.ConnectResponse;
import io.atomix.copycat.protocol.Request;
import io.atomix.copycat.protocol.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client connection that recursively connects to servers in the cluster and attempts to submit requests.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class ClientConnection {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientConnection.class);
  private final CopycatSessionState state;
  private final ConnectionManager connections;
  private final AddressSelector selector;
  private CompletableFuture<Connection> connectFuture;
  private final Map<String, Function> handlers = new ConcurrentHashMap<>();
  private Connection connection;
  private boolean open = true;

  public ClientConnection(CopycatSessionState state, ConnectionManager connections, AddressSelector selector) {
    this.state = Assert.notNull(state, "state");
    this.connections = Assert.notNull(connections, "connections");
    this.selector = Assert.notNull(selector, "selector");
  }

  /**
   * Returns the current selector leader.
   *
   * @return The current selector leader.
   */
  public Address leader() {
    return selector.leader();
  }

  /**
   * Returns the current set of servers.
   *
   * @return The current set of servers.
   */
  public Collection<Address> servers() {
    return selector.servers();
  }

  /**
   * Resets the client connection.
   *
   * @return The client connection.
   */
  public ClientConnection reset() {
    selector.reset();
    return this;
  }

  /**
   * Resets the client connection.
   *
   * @param leader The current cluster leader.
   * @param servers The current servers.
   * @return The client connection.
   */
  public ClientConnection reset(Address leader, Collection<Address> servers) {
    selector.reset(leader, servers);
    return this;
  }

  /**
   * Sends a request to the cluster.
   *
   * @param type The request type.
   * @param request The request to send.
   * @return A completable future to be completed with the response.
   */
  public CompletableFuture<Void> send(String type, Object request) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    sendRequest((Request) request, (r, c) -> c.send(type, r), future);
    return future;
  }

  /**
   * Sends a request to the cluster and awaits a response.
   *
   * @param type The request type.
   * @param request The request to send.
   * @param <T> The request type.
   * @param <U> The response type.
   * @return A completable future to be completed with the response.
   */
  public <T, U> CompletableFuture<U> sendAndReceive(String type, T request) {
    CompletableFuture<U> future = new CompletableFuture<>();
    sendRequest((Request) request, (r, c) -> c.sendAndReceive(type, r), future);
    return future;
  }

  /**
   * Sends the given request attempt to the cluster.
   */
  private <T extends Request, U> void sendRequest(T request, BiFunction<Request, Connection, CompletableFuture<U>> sender, CompletableFuture<U> future) {
    if (open) {
      connect().whenComplete((c, e) -> sendRequest(request, sender, c, e, future));
    }
  }

  /**
   * Sends the given request attempt to the cluster via the given connection if connected.
   */
  private <T extends Request, U> void sendRequest(T request, BiFunction<Request, Connection, CompletableFuture<U>> sender, Connection connection, Throwable error, CompletableFuture<U> future) {
    if (open) {
      if (error == null) {
        if (connection != null) {
          LOGGER.trace("{} - Sending {}", state.getSessionString(), request);
          sender.apply(request, connection).whenComplete((r, e) -> {
            if (e != null || r != null) {
              handleResponse(request, sender, connection, (Response) r, e, future);
            } else {
              future.complete(null);
            }
          });
        } else {
          future.completeExceptionally(new ConnectException("Failed to connect to the cluster"));
        }
      } else {
        LOGGER.trace("{} - Resending {}: {}", state.getSessionString(), request, error);
        resendRequest(error, request, sender, connection, future);
      }
    }
  }

  /**
   * Resends a request due to a request failure, resetting the connection if necessary.
   */
  @SuppressWarnings("unchecked")
  private <T extends Request> void resendRequest(Throwable cause, T request, BiFunction sender, Connection connection, CompletableFuture future) {
    // If the connection has not changed, reset it and connect to the next server.
    if (this.connection == connection) {
      LOGGER.trace("{} - Resetting connection. Reason: {}", state.getSessionString(), cause);
      this.connection = null;
    }

    // Create a new connection and resend the request. This will force retries to piggyback on any existing
    // connect attempts.
    connect().whenComplete((c, e) -> sendRequest(request, sender, c, e, future));
  }

  /**
   * Handles a response from the cluster.
   */
  @SuppressWarnings("unchecked")
  private <T extends Request> void handleResponse(T request, BiFunction sender, Connection connection, Response response, Throwable error, CompletableFuture future) {
    if (open) {
      if (error == null) {
        if (response.status() == Response.Status.OK
          || response.error() == CopycatError.Type.COMMAND_ERROR
          || response.error() == CopycatError.Type.QUERY_ERROR
          || response.error() == CopycatError.Type.APPLICATION_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_CLIENT_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_SESSION_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_STATE_MACHINE_ERROR
          || response.error() == CopycatError.Type.INTERNAL_ERROR) {
          LOGGER.trace("{} - Received {}", state.getSessionString(), response);
          future.complete(response);
        } else {
          resendRequest(response.error().createException(), request, sender, connection, future);
        }
      } else if (error instanceof ConnectException || error instanceof TimeoutException || error instanceof TransportException || error instanceof ClosedChannelException) {
        resendRequest(error, request, sender, connection, future);
      } else {
        LOGGER.debug("{} - {} failed! Reason: {}", state.getSessionString(), request, error);
        future.completeExceptionally(error);
      }
    }
  }

  /**
   * Connects to the cluster.
   */
  private CompletableFuture<Connection> connect() {
    // If the address selector has been reset then reset the connection.
    if (selector.state() == AddressSelector.State.RESET && connection != null) {
      if (connectFuture != null) {
        return connectFuture;
      }

      CompletableFuture<Connection> future = new OrderedCompletableFuture<>();
      future.whenComplete((r, e) -> this.connectFuture = null);
      this.connectFuture = future;

      this.connection = null;
      connect(future);
      return future;
    }

    // If a connection was already established then use that connection.
    if (connection != null) {
      return CompletableFuture.completedFuture(connection);
    }

    // If a connection is currently being established then piggyback on the connect future.
    if (connectFuture != null) {
      return connectFuture;
    }

    // Create a new connect future and connect to the first server in the cluster.
    CompletableFuture<Connection> future = new OrderedCompletableFuture<>();
    future.whenComplete((r, e) -> this.connectFuture = null);
    this.connectFuture = future;
    reset().connect(future);
    return future;
  }

  /**
   * Attempts to connect to the cluster.
   */
  private void connect(CompletableFuture<Connection> future) {
    if (!selector.hasNext()) {
      LOGGER.debug("{} - Failed to connect to the cluster", state.getSessionString());
      future.complete(null);
    } else {
      Address address = selector.next();
      LOGGER.debug("{} - Connecting to {}", state.getSessionString(), address);
      connections.getConnection(address).whenComplete((c, e) -> handleConnection(address, c, e, future));
    }
  }

  /**
   * Handles a connection to a server.
   */
  private void handleConnection(Address address, Connection connection, Throwable error, CompletableFuture<Connection> future) {
    if (open) {
      if (error == null) {
        setupConnection(address, connection, future);
      } else {
        LOGGER.debug("{} - Failed to connect! Reason: {}", state.getSessionString(), error);
        connect(future);
      }
    }
  }

  /**
   * Sets up the given connection.
   */
  @SuppressWarnings("unchecked")
  private void setupConnection(Address address, Connection connection, CompletableFuture<Connection> future) {
    LOGGER.debug("{} - Setting up connection to {}", state.getSessionString(), address);

    this.connection = connection;

    connection.onClose(c -> {
      if (c.equals(this.connection)) {
        LOGGER.debug("{} - Connection closed", state.getSessionString());
        this.connection = null;
      }
    });
    connection.onException(c -> {
      if (c.equals(this.connection)) {
        LOGGER.debug("{} - Connection lost", state.getSessionString());
        this.connection = null;
      }
    });

    for (Map.Entry<String, Function> entry : handlers.entrySet()) {
      connection.registerHandler(entry.getKey(), entry.getValue());
    }

    // When we first connect to a new server, first send a ConnectRequest to the server to establish
    // the connection with the server-side state machine.
    if (state.getSessionId() > 0) {
      ConnectRequest request = ConnectRequest.builder()
        .withSession(state.getSessionId())
        .withConnection(state.nextConnection())
        .build();

      LOGGER.trace("{} - Sending {}", state.getSessionString(), request);
      connection.<ConnectRequest, ConnectResponse>sendAndReceive(ConnectRequest.NAME, request).whenComplete((r, e) -> handleConnectResponse(r, e, future));
    } else {
      future.complete(connection);
    }
  }

  /**
   * Handles a connect response.
   */
  private void handleConnectResponse(ConnectResponse response, Throwable error, CompletableFuture<Connection> future) {
    if (open) {
      if (error == null) {
        LOGGER.trace("{} - Received {}", state.getSessionString(), response);
        // If the connection was successfully created, immediately send a keep-alive request
        // to the server to ensure we maintain our session and get an updated list of server addresses.
        if (response.status() == Response.Status.OK) {
          selector.reset(response.leader(), response.members());
          future.complete(connection);
        } else {
          connect(future);
        }
      } else {
        LOGGER.debug("{} - Failed to connect! Reason: {}", state.getSessionString(), error);
        connect(future);
      }
    }
  }

  /**
   * Registers a handler for the given message type.
   *
   * @param type The message type for which to register the handler.
   * @param handler The handler to register.
   * @param <T> The handler type.
   * @return The client connection.
   */
  @SuppressWarnings("unchecked")
  public <T> ClientConnection registerHandler(String type, Consumer<T> handler) {
    return registerHandler(type, r -> {
      handler.accept((T) r);
      return null;
    });
  }

  /**
   * Registers a handler for the given message type.
   *
   * @param type The message type for which to register the handler.
   * @param handler The handler to register.
   * @param <T> The handler type.
   * @param <U> The response type.
   * @return The client connection.
   */
  public <T, U> ClientConnection registerHandler(String type, Function<T, CompletableFuture<U>> handler) {
    Assert.notNull(type, "type");
    Assert.notNull(handler, "handler");
    handlers.put(type, handler);
    if (connection != null)
      connection.registerHandler(type, handler);
    return this;
  }

  /**
   * Closes the connection.
   *
   * @return A completable future to be completed once the connection is closed.
   */
  public CompletableFuture<Void> close() {
    open = false;
    return CompletableFuture.completedFuture(null);
  }

}
