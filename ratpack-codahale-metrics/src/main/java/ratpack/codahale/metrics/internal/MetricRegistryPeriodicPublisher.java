/*
 * Copyright 2014 the original author or authors.
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

package ratpack.codahale.metrics.internal;

import com.codahale.metrics.MetricRegistry;
import ratpack.codahale.metrics.CodaHaleMetricsModule;
import ratpack.exec.ExecController;
import ratpack.stream.internal.PeriodicPublisher;

import javax.inject.Inject;

public class MetricRegistryPeriodicPublisher extends PeriodicPublisher<MetricRegistry> {

  @Inject
  public MetricRegistryPeriodicPublisher(final MetricRegistry metricRegistry, CodaHaleMetricsModule.Config config, ExecController execController) {
    super(
      execController.getExecutor(),
      i -> metricRegistry,
      config.getWebSocket().getReporterInterval()
    );
  }

}
