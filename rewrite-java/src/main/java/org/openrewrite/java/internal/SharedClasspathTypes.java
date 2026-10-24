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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Incubating;
import org.openrewrite.java.tree.JavaType;

import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * JVM-wide tables of {@link JavaType}s mapped from classpath jars and the JDK, one table per parser
 * environment (parser implementation, JDK, and classpath identity), so parsers that share an environment
 * map each classpath type once.
 * <p>
 * A table only ever holds types whose whole reachable type graph consists of classes read from class files.
 * Types declared by parsed sources, types that reach them, and types that reach {@link JavaType.Unknown}
 * (which stands in for a missing class whose name a later parse could declare) stay private to their parser.
 * A parser whose sources declare a class the table already holds must not use the table at all, because the
 * shared types reference the class-file version of that class.
 * <p>
 * Shared instances are reachable from the trees of every parse that uses the table, so they must never be
 * mutated after they are shared.
 */
@Incubating(since = "8.94.0")
public final class SharedClasspathTypes {

    private static final int MAX_TABLES = 8;

    private static final Map<String, SoftReference<Table>> TABLES = new LinkedHashMap<String, SoftReference<Table>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SoftReference<Table>> eldest) {
            return size() > MAX_TABLES;
        }
    };

    public static synchronized Table table(String environmentKey) {
        SoftReference<Table> ref = TABLES.get(environmentKey);
        Table table = ref == null ? null : ref.get();
        if (table == null) {
            table = new Table();
            TABLES.put(environmentKey, new SoftReference<>(table));
        }
        return table;
    }

    /**
     * Drop every table. For tests.
     */
    public static synchronized void clear() {
        TABLES.clear();
    }

    private SharedClasspathTypes() {
    }

    public static final class Table {
        private final Map<String, Object> byKey = new ConcurrentHashMap<>();
        private final Set<String> classNames = ConcurrentHashMap.newKeySet();

        /**
         * Every shared instance, by identity. Guarded by {@code this}.
         */
        private final Set<Object> instances = Collections.newSetFromMap(new IdentityHashMap<>());

        @Nullable
        Object get(String key) {
            return byKey.get(key);
        }

        public boolean containsClass(String fullyQualifiedName) {
            return classNames.contains(fullyQualifiedName);
        }

        public int size() {
            return byKey.size();
        }

        /**
         * Share each candidate whose reachable type graph is free of source and missing types.
         *
         * @param candidates  types a parser built or looked up, by cache key; every one must be fully initialized
         * @param isClassFile whether the class with this fully qualified name was read from a class file
         */
        synchronized void promote(Map<String, Object> candidates, Predicate<String> isClassFile) {
            Map<Object, Boolean> visited = new IdentityHashMap<>();
            Map<Object, List<Object>> referrers = new IdentityHashMap<>();
            Deque<Object> impure = new ArrayDeque<>();
            Deque<Object> stack = new ArrayDeque<>();
            for (Object candidate : candidates.values()) {
                if (!instances.contains(candidate)) {
                    stack.push(candidate);
                }
            }
            List<Object> children = new ArrayList<>();
            while (!stack.isEmpty()) {
                Object t = stack.pop();
                if (visited.put(t, Boolean.TRUE) != null) {
                    continue;
                }
                children.clear();
                if (!children(t, children, isClassFile)) {
                    impure.add(t);
                }
                for (Object child : children) {
                    if (child == null || child instanceof JavaType.Primitive || instances.contains(child)) {
                        continue;
                    }
                    referrers.computeIfAbsent(child, k -> new ArrayList<>(2)).add(t);
                    if (!visited.containsKey(child)) {
                        stack.push(child);
                    }
                }
            }

            Set<Object> tainted = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!impure.isEmpty()) {
                Object t = impure.poll();
                if (tainted.add(t)) {
                    List<Object> refs = referrers.get(t);
                    if (refs != null) {
                        impure.addAll(refs);
                    }
                }
            }

            for (Object t : visited.keySet()) {
                if (!tainted.contains(t)) {
                    instances.add(t);
                    if (t instanceof JavaType.Class) {
                        classNames.add(((JavaType.Class) t).getFullyQualifiedName());
                    }
                }
            }
            for (Map.Entry<String, Object> candidate : candidates.entrySet()) {
                Object t = candidate.getValue();
                if (!tainted.contains(t) && instances.contains(t)) {
                    byKey.putIfAbsent(candidate.getKey(), t);
                }
            }
        }

        /**
         * Adds the types {@code t} references to {@code children}.
         *
         * @return false when {@code t} itself must not be shared
         */
        private static boolean children(Object t, List<Object> children, Predicate<String> isClassFile) {
            if (t instanceof JavaType.Unknown || t instanceof JavaType.ShallowClass) {
                return false;
            } else if (t instanceof JavaType.Primitive) {
                return true;
            } else if (t instanceof JavaType.Annotation) {
                JavaType.Annotation a = (JavaType.Annotation) t;
                children.add(a.getType());
                for (JavaType.Annotation.ElementValue ev : a.getValues()) {
                    children.add(ev.getElement());
                    if (ev instanceof JavaType.Annotation.SingleElementValue) {
                        children.add(((JavaType.Annotation.SingleElementValue) ev).getReferenceValue());
                    } else if (ev instanceof JavaType.Annotation.ArrayElementValue) {
                        JavaType[] refs = ((JavaType.Annotation.ArrayElementValue) ev).getReferenceValues();
                        if (refs != null) {
                            Collections.addAll(children, (Object[]) refs);
                        }
                    }
                }
                return true;
            } else if (t instanceof JavaType.Parameterized) {
                JavaType.Parameterized p = (JavaType.Parameterized) t;
                children.add(p.getType());
                children.addAll(p.getTypeParameters());
                return true;
            } else if (t instanceof JavaType.Class) {
                JavaType.Class c = (JavaType.Class) t;
                children.addAll(c.getTypeParameters());
                children.add(c.getSupertype());
                children.add(c.getOwningClass());
                children.addAll(c.getAnnotations());
                children.addAll(c.getInterfaces());
                children.addAll(c.getMembers());
                children.addAll(c.getMethods());
                return isClassFile.test(c.getFullyQualifiedName());
            } else if (t instanceof JavaType.GenericTypeVariable) {
                children.addAll(((JavaType.GenericTypeVariable) t).getBounds());
                return true;
            } else if (t instanceof JavaType.Array) {
                JavaType.Array a = (JavaType.Array) t;
                children.add(a.getElemType());
                children.addAll(a.getAnnotations());
                return true;
            } else if (t instanceof JavaType.Intersection) {
                children.addAll(((JavaType.Intersection) t).getBounds());
                return true;
            } else if (t instanceof JavaType.MultiCatch) {
                children.addAll(((JavaType.MultiCatch) t).getThrowableTypes());
                return true;
            } else if (t instanceof JavaType.Method) {
                JavaType.Method m = (JavaType.Method) t;
                children.add(m.getDeclaringType());
                children.add(m.getReturnType());
                children.addAll(m.getParameterTypes());
                children.addAll(m.getThrownExceptions());
                children.addAll(m.getAnnotations());
                return true;
            } else if (t instanceof JavaType.Variable) {
                JavaType.Variable v = (JavaType.Variable) t;
                children.add(v.getOwner());
                children.add(v.getType());
                children.addAll(v.getAnnotations());
                return true;
            }
            return false;
        }
    }
}
