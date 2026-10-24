/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.tree;

import org.jspecify.annotations.Nullable;

final class SameElements {

    /**
     * Whether both arrays hold the same instances in the same order, treating null as empty.
     */
    static boolean of(Object @Nullable [] a, Object @Nullable [] b) {
        if (a == b) {
            return true;
        }
        int length = a == null ? 0 : a.length;
        if (length != (b == null ? 0 : b.length)) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            //noinspection DataFlowIssue
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private SameElements() {
    }
}
