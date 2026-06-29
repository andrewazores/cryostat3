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
package io.cryostat.recordings;

/**
 * Typed representation of a single {@code -XX:StartFlightRecording=<params>} JVM argument entry, as
 * parsed from {@link java.lang.management.RuntimeMXBean#getInputArguments()}.
 *
 * <p>All duration and age values are in milliseconds. A value of {@code 0} for {@code durationMs}
 * means continuous (no fixed end time).
 */
public record ExternalRecordingDescriptor(
        String name,
        String settings,
        long durationMs,
        boolean disk,
        long maxSizeBytes,
        long maxAgeMs) {}
