/*
 * Copyright The Cryostat Authors.
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
package io.cryostat;

import io.cryostat.recordings.ActiveRecordings.LinkedRecordingDescriptor;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.LocalEventBusCodec;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

public class MessageCodecs {

    @Inject EventBus bus;

    void onStart(@Observes StartupEvent evt) {
        bus.getDelegate()
                .registerDefaultCodec(LinkedRecordingDescriptor.class, new LocalEventBusCodec<>());
    }
}
