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

import java.time.Duration;

import io.cryostat.ConfigProperties;
import io.cryostat.targets.Target;

import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.ObjectDeletedException;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * One-shot Quartz job that fires when all detected fixed-duration external recordings on a target
 * are expected to have completed. Creates a snapshot of the accumulated recording data (captured by
 * the {@code cryostat-retainer} continuous recording), archives it, then deletes both the snapshot
 * and the retainer recording.
 *
 * @see RecordingHelper#startRetainerIfNeeded
 */
@DisallowConcurrentExecution
public class RetainerHarvestJob implements Job {

    static final String JOB_GROUP = "retainer-harvest";
    static final String DATA_TARGET_ID = "targetId";
    static final String DATA_RETAINER_RECORDING_ID = "retainerRecordingId";

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @Override
    @Transactional
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        long targetId = ctx.getMergedJobDataMap().getLong(DATA_TARGET_ID);
        long retainerRecordingId = ctx.getMergedJobDataMap().getLong(DATA_RETAINER_RECORDING_ID);

        Target target;
        try {
            target = Target.getTargetById(targetId);
        } catch (NoResultException | ObjectDeletedException e) {
            logger.debugv(
                    "Target {0} no longer exists when RetainerHarvestJob fired, unscheduling",
                    targetId);
            var jee = new JobExecutionException(e);
            jee.setUnscheduleFiringTrigger(true);
            jee.setRefireImmediately(false);
            throw jee;
        }

        ActiveRecording retainerRecording = ActiveRecording.findById(retainerRecordingId);
        if (retainerRecording == null) {
            logger.warnv(
                    "Retainer recording {0} not found when RetainerHarvestJob fired for target"
                            + " {1}, continuing with snapshot attempt",
                    retainerRecordingId, target.connectUrl);
        }

        try {
            recordingHelper
                    .harvestRetainer(target, retainerRecording)
                    .await()
                    .atMost(connectionFailedTimeout);
        } catch (Exception e) {
            logger.errorv(e, "RetainerHarvestJob failed for target {0}", target.connectUrl);
            var jee = new JobExecutionException(e);
            jee.setUnscheduleFiringTrigger(true);
            jee.setRefireImmediately(false);
            throw jee;
        }
    }
}
