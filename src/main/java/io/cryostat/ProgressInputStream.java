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

import java.io.InputStream;
import java.util.function.Consumer;

import org.apache.commons.io.input.ProxyInputStream;

/**
 * An InputStream which informs a provided {@link java.util.function.Consumer} about the number of
 * bytes read each time a chunk is read from this stream.
 */
public class ProgressInputStream extends ProxyInputStream {

    private final Consumer<Integer> onUpdate;

    public ProgressInputStream(InputStream delegate, Consumer<Integer> onUpdate) {
        super(delegate);
        this.onUpdate = onUpdate;
    }

    @Override
    protected void afterRead(int n) {
        if (n < 0) {
            return;
        }
        onUpdate.accept(n);
    }
}
