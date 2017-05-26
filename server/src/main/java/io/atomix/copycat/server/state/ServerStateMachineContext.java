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
package io.atomix.copycat.server.state;

import io.atomix.copycat.server.StateMachineContext;
import io.atomix.copycat.server.session.Sessions;

import java.time.Clock;
import java.time.Instant;

/**
 * Server state machine context.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
class ServerStateMachineContext implements StateMachineContext {

  /**
   * Context type.
   */
  enum Type {
    COMMAND,
    QUERY,
  }

  private final ServerClock clock = new ServerClock();
  private final ServerStateMachineSessions sessions;
  private Type type;
  private long index;

  public ServerStateMachineContext(ServerStateMachineSessions sessions) {
    this.sessions = sessions;
  }

  /**
   * Updates the state machine context.
   */
  void update(long index, Instant instant, Type type) {
    this.index = index;
    this.type = type;
    clock.set(instant);
  }

  /**
   * Commits the state machine index.
   */
  void commit() {
    long index = this.index;
    for (ServerSessionContext session : sessions.sessions.values()) {
      session.commit(index);
    }
  }

  /**
   * Returns the current context type.
   */
  Type type() {
    return type;
  }

  @Override
  public long index() {
    return index;
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public Sessions sessions() {
    return sessions;
  }

  @Override
  public String toString() {
    return String.format("%s[index=%d, time=%s]", getClass().getSimpleName(), index, clock);
  }

}