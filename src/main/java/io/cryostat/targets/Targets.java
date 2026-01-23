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
package io.cryostat.targets;

import java.time.Duration;
import java.util.List;

import io.cryostat.ConfigProperties;
import io.cryostat.expressions.MatchExpressionEvaluator;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class Targets {

    @Inject MatchExpressionEvaluator matchExpressionEvaluator;
    @Inject TargetConnectionManager connectionManager;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    @GET
    @Path("/api/v4/targets")
    @RolesAllowed("read")
    @Operation(
            summary = "List currently discovered targets",
            description =
                    """
                    Get a list of the currently discovered targets. These are essentially the same as the leaf nodes of
                    the discovery tree. See 'GET /api/v4/discovery'. By default, only active (non-deleted) targets are
                    returned. Use the 'includeDeleted' query parameter to include soft-deleted targets.
                    """)
    public List<Target> list(
            @Parameter(
                            description =
                                    "Include soft-deleted targets in the response. Default is"
                                            + " false.")
                    @QueryParam("includeDeleted")
                    @DefaultValue("false")
                    boolean includeDeleted) {
        if (includeDeleted) {
            return Target.findAllIncludingDeleted();
        }
        return Target.findActive();
    }

    @GET
    @Path("/api/v4/targets/{id}")
    @RolesAllowed("read")
    @Operation(
            summary = "Get a target by ID",
            description =
                    """
                    Get details about a particular target given its ID. This endpoint returns the target
                    even if it has been soft-deleted.
                    """)
    public Target getById(@RestPath Long id) {
        return Target.getTargetById(id);
    }
}
