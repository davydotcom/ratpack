/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.groovy.internal;

import groovy.lang.Closure;

public class RatpackDslClosures {

  private final Closure<?> handlers;
  private final Closure<?> bindings;
  private final Closure<?> config;

  public RatpackDslClosures(Closure<?> config, Closure<?> handlers, Closure<?> bindings) {
    this.config = config;
    this.handlers = handlers;
    this.bindings = bindings;
  }

  public Closure<?> getHandlers() {
    return handlers;
  }

  public Closure<?> getBindings() {
    return bindings;
  }

  public Closure<?> getConfig() {
    return config;
  }

}
