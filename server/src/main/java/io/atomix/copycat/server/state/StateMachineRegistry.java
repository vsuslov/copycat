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

import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.server.StateMachine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * State machine registry.
 */
public class StateMachineRegistry {
  private final Map<String, Supplier<StateMachine>> stateMachines = new ConcurrentHashMap<>();

  /**
   * Returns the number of registered state machines.
   *
   * @return The number of registered state machines.
   */
  public int size() {
    return stateMachines.size();
  }

  /**
   * Registers a new state machine type.
   *
   * @param type The state machine type to register.
   * @param factory The state machine factory.
   * @return The state machine registry.
   */
  public StateMachineRegistry register(String type, Supplier<StateMachine> factory) {
    stateMachines.put(Assert.notNull(type, "type"), Assert.notNull(factory, "factory"));
    return this;
  }

  /**
   * Unregisters the given state machine type.
   *
   * @param type The state machine type to unregister.
   * @return The state machine registry.
   */
  public StateMachineRegistry unregister(String type) {
    stateMachines.remove(type);
    return this;
  }

  /**
   * Returns the factory for the given state machine type.
   *
   * @param type The state machine type for which to return the factory.
   * @return The factory for the given state machine type or {@code null} if the type is not registered.
   */
  public Supplier<StateMachine> getFactory(String type) {
    return stateMachines.get(type);
  }

  @Override
  public String toString() {
    return String.format("%s[stateMachines=%s]", getClass().getSimpleName(), stateMachines);
  }

}
