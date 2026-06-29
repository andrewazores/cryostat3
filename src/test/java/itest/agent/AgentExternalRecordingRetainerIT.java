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
package itest.agent;

import static io.restassured.RestAssured.given;

import java.time.Duration;

import io.cryostat.recordings.RecordingHelper;
import io.cryostat.resources.AgentExternalRecordingRetainerApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the retainer recording strategy. A target JVM is started with a short
 * fixed-duration {@code -XX:StartFlightRecording} flag. Cryostat should:
 *
 * <ol>
 *   <li>Detect the flag via JVM input arguments during the first sync.
 *   <li>Start a {@code cryostat-retainer} continuous recording on the target.
 *   <li>After the external recording's expected end time, create a snapshot, archive it, and delete
 *       both the snapshot and the retainer recording.
 * </ol>
 */
@QuarkusIntegrationTest
@QuarkusTestResource(
        value = AgentExternalRecordingRetainerApplicationResource.class,
        restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentExternalRecordingRetainerIT extends AgentTestBase {

    @Test
    void testRetainerRecordingLifecycle() throws Exception {
        // Wait for first sync to complete and retainer to be started (sync fires ~1s after
        // discovery)
        Thread.sleep(5000);

        var response =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .get("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonArray recordings = new JsonArray(response.body().asString());

        boolean externalRecordingFound = false;
        boolean retainerFound = false;
        for (int i = 0; i < recordings.size(); i++) {
            JsonObject r = recordings.getJsonObject(i);
            String name = r.getString("name");
            if (AgentExternalRecordingRetainerApplicationResource.RECORDING_NAME.equals(name)) {
                externalRecordingFound = true;
            }
            if (RecordingHelper.RETAINER_RECORDING_NAME.equals(name)) {
                retainerFound = true;
            }
        }

        MatcherAssert.assertThat(
                "External recording should be present after initial sync",
                externalRecordingFound,
                Matchers.is(true));
        MatcherAssert.assertThat(
                "cryostat-retainer should be present after initial sync",
                retainerFound,
                Matchers.is(true));

        // Wait for harvest: duration + delay + buffer for job execution and archival
        long harvestWaitSeconds =
                AgentExternalRecordingRetainerApplicationResource.RECORDING_DURATION_SECONDS + 30L;
        webSocketClient.expectNotification(
                "ArchivedRecordingCreated", Duration.ofSeconds(harvestWaitSeconds));

        // Allow a moment for deletion to complete
        Thread.sleep(3000);

        var afterResponse =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .get("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonArray afterRecordings = new JsonArray(afterResponse.body().asString());

        boolean retainerStillPresent = false;
        for (int i = 0; i < afterRecordings.size(); i++) {
            JsonObject r = afterRecordings.getJsonObject(i);
            if (RecordingHelper.RETAINER_RECORDING_NAME.equals(r.getString("name"))) {
                retainerStillPresent = true;
                break;
            }
        }

        MatcherAssert.assertThat(
                "cryostat-retainer should be deleted after harvest",
                retainerStillPresent,
                Matchers.is(false));
    }
}
