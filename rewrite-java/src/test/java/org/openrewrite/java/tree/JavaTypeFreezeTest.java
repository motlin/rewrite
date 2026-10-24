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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaTypeFreezeTest {

    private static final JavaType.Class STRING = JavaType.ShallowClass.build("java.lang.String");
    private static final JavaType.Class INTEGER = JavaType.ShallowClass.build("java.lang.Integer");
    private static final JavaType.Class LIST = JavaType.ShallowClass.build("java.util.List");

    @Test
    void unfrozenTypeChangesInPlace() {
        JavaType.Parameterized list = new JavaType.Parameterized(null, LIST, List.of(STRING));

        JavaType.Parameterized changed = list.unsafeSet(LIST, List.of(INTEGER));

        assertThat(changed).isSameAs(list);
        assertThat(list.toString()).isEqualTo("java.util.List<java.lang.Integer>");
    }

    @Test
    void frozenParameterizedIsCopiedOnChange() {
        JavaType.Parameterized list = new JavaType.Parameterized(null, LIST, List.of(STRING));
        list.freeze();

        JavaType.Parameterized changed = list.unsafeSet(LIST, List.of(INTEGER));

        assertThat(changed).isNotSameAs(list);
        assertThat(changed.toString()).isEqualTo("java.util.List<java.lang.Integer>");
        assertThat(list.toString()).isEqualTo("java.util.List<java.lang.String>");
        assertThat(list.unsafeSet(LIST, List.of(STRING))).isSameAs(list);
    }

    @Test
    void copyOfAFrozenTypeIsNotFrozen() {
        JavaType.Parameterized list = new JavaType.Parameterized(null, LIST, List.of(STRING));
        list.freeze();
        JavaType.Parameterized copy = list.unsafeSet(LIST, List.of(INTEGER));
        JavaType.Parameterized withCopy = list.withTypeParameters(List.of(INTEGER));

        assertThat(copy.unsafeSet(LIST, List.of(STRING))).isSameAs(copy);
        assertThat(withCopy.unsafeSet(LIST, List.of(STRING))).isSameAs(withCopy);
    }

    @Test
    void frozenClassIsCopiedOnChange() {
        JavaType.Method m = new JavaType.Method(null, 1, null, "m", STRING, (List<String>) null, null, null, null, null, null);
        JavaType.Class c = new JavaType.Class(null, 1, "p.C", JavaType.FullyQualified.Kind.Class, null, null, null, null, null, null, List.of(m));
        c.freeze();

        JavaType.Class changed = c.unsafeSet(List.of(), null, null, List.of(), List.of(), List.of(), List.of());
        JavaType.Class renamed = c.unsafeSet(1, JavaType.FullyQualified.Kind.Class, "p.D", null, null, null, null, null, null, List.of(m));

        assertThat(changed.getMethods()).isEmpty();
        assertThat(renamed.getFullyQualifiedName()).isEqualTo("p.D");
        assertThat(c.getMethods()).containsExactly(m);
        assertThat(c.getFullyQualifiedName()).isEqualTo("p.C");
        assertThat(c.unsafeSet(List.of(), null, null, List.of(), List.of(), List.of(), List.of(m))).isSameAs(c);
    }

    @Test
    void frozenMethodIsCopiedOnChange() {
        JavaType.Method m = new JavaType.Method(null, 1, LIST, "m", STRING, List.of("a"), List.of(STRING), null, null, null, null);
        m.freeze();

        JavaType.Method changed = m.unsafeSet(LIST, INTEGER, List.of(STRING), List.of(), List.of());

        assertThat(changed.getReturnType()).isSameAs(INTEGER);
        assertThat(m.getReturnType()).isSameAs(STRING);
        assertThat(m.unsafeSet(LIST, STRING, List.of(STRING), List.of(), List.of())).isSameAs(m);
    }

    @Test
    void frozenVariableIsCopiedOnChange() {
        JavaType.Variable v = new JavaType.Variable(null, 1, "v", LIST, STRING, (List<JavaType.FullyQualified>) null);
        v.freeze();

        JavaType.Variable changed = v.unsafeSet(LIST, INTEGER, (List<JavaType.FullyQualified>) null);

        assertThat(changed.getType()).isSameAs(INTEGER);
        assertThat(v.getType()).isSameAs(STRING);
        assertThat(v.unsafeSet(LIST, STRING, (List<JavaType.FullyQualified>) null)).isSameAs(v);
    }

    @Test
    void frozenGenericTypeVariableIsCopiedOnChange() {
        JavaType.GenericTypeVariable t = new JavaType.GenericTypeVariable(null, "T", JavaType.GenericTypeVariable.Variance.COVARIANT, List.of(STRING));
        t.freeze();

        JavaType.GenericTypeVariable changed = t.unsafeSet("T", JavaType.GenericTypeVariable.Variance.COVARIANT, List.of(INTEGER));

        assertThat(changed.getBounds()).containsExactly(INTEGER);
        assertThat(t.getBounds()).containsExactly(STRING);
        assertThat(t.unsafeSet("T", JavaType.GenericTypeVariable.Variance.COVARIANT, List.of(STRING))).isSameAs(t);
    }

    @Test
    void frozenArrayIntersectionMultiCatchAndAnnotationAreCopiedOnChange() {
        JavaType.Array array = new JavaType.Array(null, STRING, null);
        JavaType.Intersection intersection = new JavaType.Intersection(List.of(STRING));
        JavaType.MultiCatch multiCatch = new JavaType.MultiCatch(List.of(STRING));
        JavaType.Annotation annotation = new JavaType.Annotation(STRING, List.of());
        array.freeze();
        intersection.freeze();
        multiCatch.freeze();
        annotation.freeze();

        assertThat(array.unsafeSet(INTEGER, null).getElemType()).isSameAs(INTEGER);
        assertThat(intersection.unsafeSet(List.of(INTEGER)).getBounds()).containsExactly(INTEGER);
        assertThat(multiCatch.unsafeSet(List.of(INTEGER)).getThrowableTypes()).containsExactly(INTEGER);
        assertThat(annotation.unsafeSet(INTEGER, null).getType()).isSameAs(INTEGER);

        assertThat(array.getElemType()).isSameAs(STRING);
        assertThat(intersection.getBounds()).containsExactly(STRING);
        assertThat(multiCatch.getThrowableTypes()).containsExactly(STRING);
        assertThat(annotation.getType()).isSameAs(STRING);

        assertThat(array.unsafeSet(STRING, null)).isSameAs(array);
        assertThat(intersection.unsafeSet(List.of(STRING))).isSameAs(intersection);
        assertThat(multiCatch.unsafeSet(List.of(STRING))).isSameAs(multiCatch);
        assertThat(annotation.unsafeSet(STRING, null)).isSameAs(annotation);
    }

    @Test
    void frozenFlagIsNotSerialized() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
        JavaType.Variable v = new JavaType.Variable(null, 1, "v", null, STRING, (List<JavaType.FullyQualified>) null);
        v.freeze();

        assertThat(mapper.writeValueAsString(v)).doesNotContain("frozen");
    }
}
