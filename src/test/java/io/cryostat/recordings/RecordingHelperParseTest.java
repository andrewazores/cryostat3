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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class RecordingHelperParseTest {

    @Test
    void nullInputReturnsEmptyList() {
        assertThat(RecordingHelper.parseStartFlightRecordingArgs(null), empty());
    }

    @Test
    void emptyArrayReturnsEmptyList() {
        assertThat(RecordingHelper.parseStartFlightRecordingArgs(new String[0]), empty());
    }

    @Test
    void noStartFlightRecordingArgReturnsEmptyList() {
        assertThat(
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-Xmx512m", "-Dfoo=bar"}),
                empty());
    }

    @Test
    void singleFlagWithAllParams() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {
                            "-XX:StartFlightRecording=name=myrecording,settings=profile,duration=120s,disk=true"
                        });
        assertThat(result, hasSize(1));
        ExternalRecordingDescriptor d = result.get(0);
        assertThat(d.name(), is("myrecording"));
        assertThat(d.settings(), is("profile"));
        assertThat(d.durationMs(), is(120_000L));
        assertThat(d.disk(), is(true));
    }

    @Test
    void multipleFlagsReturnOneEntryEach() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {
                            "-XX:StartFlightRecording=name=first,duration=30s",
                            "-XX:StartFlightRecording=name=second,duration=60s"
                        });
        assertThat(result, hasSize(2));
        assertThat(result.get(0).name(), is("first"));
        assertThat(result.get(0).durationMs(), is(30_000L));
        assertThat(result.get(1).name(), is("second"));
        assertThat(result.get(1).durationMs(), is(60_000L));
    }

    @Test
    void flagWithoutNameHasEmptyNameField() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=duration=60s"});
        assertThat(result, hasSize(1));
        assertThat(result.get(0).name(), is(""));
    }

    @Test
    void durationInSeconds() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=duration=30s"});
        assertThat(result.get(0).durationMs(), is(30_000L));
    }

    @Test
    void durationInMinutes() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=duration=2m"});
        assertThat(result.get(0).durationMs(), is(120_000L));
    }

    @Test
    void durationInMilliseconds() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=duration=500ms"});
        assertThat(result.get(0).durationMs(), is(500L));
    }

    @Test
    void durationInHours() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=duration=1h"});
        assertThat(result.get(0).durationMs(), is(3_600_000L));
    }

    @Test
    void zeroDurationMeansContinuous() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=name=cont,duration=0"});
        assertThat(result.get(0).durationMs(), is(0L));
    }

    @Test
    void absentDurationDefaultsToZero() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=name=noduration"});
        assertThat(result.get(0).durationMs(), is(0L));
    }

    @Test
    void diskFalse() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=disk=false"});
        assertThat(result.get(0).disk(), is(false));
    }

    @Test
    void absentDiskDefaultsToTrue() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=name=x"});
        assertThat(result.get(0).disk(), is(true));
    }

    @Test
    void maxSizeInBytes() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=maxsize=1048576"});
        assertThat(result.get(0).maxSizeBytes(), is(1_048_576L));
    }

    @Test
    void maxSizeInMegabytes() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=maxsize=10m"});
        assertThat(result.get(0).maxSizeBytes(), is(10L * 1024 * 1024));
    }

    @Test
    void maxSizeInKilobytes() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=maxsize=512k"});
        assertThat(result.get(0).maxSizeBytes(), is(512L * 1024));
    }

    @Test
    void maxSizeInGigabytes() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=maxsize=2g"});
        assertThat(result.get(0).maxSizeBytes(), is(2L * 1024 * 1024 * 1024));
    }

    @Test
    void maxAgeInSeconds() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=maxage=60s"});
        assertThat(result.get(0).maxAgeMs(), is(60_000L));
    }

    @Test
    void unknownKeysAreIgnored() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {
                            "-XX:StartFlightRecording=name=x,unknownkey=whatever,duration=10s"
                        });
        assertThat(result, hasSize(1));
        assertThat(result.get(0).name(), is("x"));
        assertThat(result.get(0).durationMs(), is(10_000L));
    }

    @Test
    void tokenWithoutEqualsIsIgnored() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=name=x,noequalstoken,duration=5s"});
        assertThat(result, hasSize(1));
        assertThat(result.get(0).name(), is("x"));
        assertThat(result.get(0).durationMs(), is(5_000L));
    }

    @Test
    void mixedWithOtherJvmArgs() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {
                            "-Xmx512m",
                            "-XX:StartFlightRecording=name=myrec,duration=45s",
                            "-Dfoo=bar"
                        });
        assertThat(result, hasSize(1));
        assertThat(result.get(0).name(), is("myrec"));
        assertThat(result.get(0).durationMs(), is(45_000L));
    }

    @Test
    void settingsFieldPreserved() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=name=x,settings=profile"});
        assertThat(result.get(0).settings(), is("profile"));
    }

    @Test
    void absentSettingsDefaultsToEmptyString() {
        var result =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {"-XX:StartFlightRecording=name=x"});
        assertThat(result.get(0).settings(), is(""));
    }

    @Test
    void filterFiniteDurationDescriptors() {
        List<ExternalRecordingDescriptor> all =
                RecordingHelper.parseStartFlightRecordingArgs(
                        new String[] {
                            "-XX:StartFlightRecording=name=finite,duration=30s",
                            "-XX:StartFlightRecording=name=continuous"
                        });
        List<ExternalRecordingDescriptor> finite =
                all.stream().filter(d -> d.durationMs() > 0).toList();
        assertThat(finite, hasSize(1));
        assertThat(finite.get(0).name(), is("finite"));
    }
}
