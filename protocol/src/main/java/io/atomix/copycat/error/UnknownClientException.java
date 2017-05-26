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
package io.atomix.copycat.error;

/**
 * Indicates that an operation or other request from an unknown client was received.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class UnknownClientException extends CopycatException {
  private static final CopycatError.Type TYPE = CopycatError.Type.UNKNOWN_CLIENT_ERROR;

  public UnknownClientException(String message, Object... args) {
    super(TYPE, message, args);
  }

  public UnknownClientException(Throwable cause, String message, Object... args) {
    super(TYPE, cause, message, args);
  }

  public UnknownClientException(Throwable cause) {
    super(TYPE, cause);
  }

}
