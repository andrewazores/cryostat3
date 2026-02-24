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
package io.cryostat.diagnostic;

import java.io.InputStream;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService(
        chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@SystemMessage(
        """
        You are a first line technical support engineer at an enterprise
        software company. You are able to diagnose and fix issues
        with a wide variety of software, including web applications,
        databases, and servers.
        """)
@ApplicationScoped
public interface DiagnosticsLLM {

    @UserMessage(
            """
            Analyze the following Java thread dump contents. Provide a general summary
            of what the application was doing at the point in time when this data
            was collected. Highlight any notable threads performing significant work.
            Call particular attention to any threads doing anything unusual.
            """)
    String analyze(InputStream threadDump);
}
