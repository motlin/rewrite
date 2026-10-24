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
package org.openrewrite.java.internal;

import org.openrewrite.Incubating;
import org.openrewrite.java.tree.JavaType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Answers from a {@link SharedClasspathTypes.Table} first and otherwise from the parser's own factory,
 * remembering what the parser's factory returned so {@link #promote} can share the classpath types among them.
 * Used for one parse; not thread-safe.
 */
@Incubating(since = "8.94.0")
public class SharingJavaTypeFactory implements JavaTypeFactory {

    private final JavaTypeFactory delegate;
    private final SharedClasspathTypes.Table table;
    private final Map<String, Object> candidates = new HashMap<>();

    public SharingJavaTypeFactory(JavaTypeFactory delegate, SharedClasspathTypes.Table table) {
        this.delegate = delegate;
        this.table = table;
    }

    /**
     * Share the classpath types returned since the last call. Call only when no type is half built, that is
     * between compilation units and never after the type mapping threw.
     */
    public void promote(Predicate<String> isClassFile) {
        if (!candidates.isEmpty()) {
            table.promote(candidates, isClassFile);
            candidates.clear();
        }
    }

    /**
     * Forget the types returned since the last {@link #promote}, for when some of them may be half built.
     */
    public void discard() {
        candidates.clear();
    }

    private <T> T remember(String key, T type) {
        candidates.put(key, type);
        return type;
    }

    @Override
    public JavaType.Class computeClass(String fqn, long flags, JavaType.FullyQualified.Kind kind,
                                       Consumer<JavaType.Class> initializer) {
        Object shared = table.get(fqn);
        if (shared instanceof JavaType.Class) {
            return (JavaType.Class) shared;
        }
        return remember(fqn, delegate.computeClass(fqn, flags, kind, initializer));
    }

    @Override
    public JavaType.Parameterized computeParameterized(String signature, Consumer<JavaType.Parameterized> initializer) {
        Object shared = table.get(signature);
        if (shared instanceof JavaType.Parameterized) {
            return (JavaType.Parameterized) shared;
        }
        return remember(signature, delegate.computeParameterized(signature, initializer));
    }

    @Override
    public JavaType.GenericTypeVariable computeGenericTypeVariable(String signature, String name,
                                                                   JavaType.GenericTypeVariable.Variance variance,
                                                                   Consumer<JavaType.GenericTypeVariable> initializer) {
        Object shared = table.get(signature);
        if (shared instanceof JavaType.GenericTypeVariable) {
            return (JavaType.GenericTypeVariable) shared;
        }
        return remember(signature, delegate.computeGenericTypeVariable(signature, name, variance, initializer));
    }

    @Override
    public JavaType.Method methodFor(String signature, Supplier<JavaType.Method> stub,
                                     Consumer<JavaType.Method> initializer) {
        Object shared = table.get(signature);
        if (shared instanceof JavaType.Method) {
            return (JavaType.Method) shared;
        }
        return remember(signature, delegate.methodFor(signature, stub, initializer));
    }

    @Override
    public JavaType.Variable variableFor(String signature, Supplier<JavaType.Variable> builder) {
        Object shared = table.get(signature);
        if (shared instanceof JavaType.Variable) {
            return (JavaType.Variable) shared;
        }
        return remember(signature, delegate.variableFor(signature, builder));
    }

    @Override
    public JavaType.Array arrayFor(String signature, Supplier<JavaType.Array> builder) {
        Object shared = table.get(signature);
        if (shared instanceof JavaType.Array) {
            return (JavaType.Array) shared;
        }
        return remember(signature, delegate.arrayFor(signature, builder));
    }

    @Override
    public JavaType.Intersection intersectionFor(String signature, Supplier<JavaType.Intersection> builder) {
        Object shared = table.get(signature);
        if (shared instanceof JavaType.Intersection) {
            return (JavaType.Intersection) shared;
        }
        return remember(signature, delegate.intersectionFor(signature, builder));
    }
}
