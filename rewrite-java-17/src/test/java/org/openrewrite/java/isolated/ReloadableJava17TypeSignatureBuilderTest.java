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
package org.openrewrite.java.isolated;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.Java17Parser;
import org.openrewrite.java.JavaParser;

import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadableJava17TypeSignatureBuilderTest {

    @TempDir
    static Path fixtureClasses;

    @BeforeAll
    static void compileFixture() throws Exception {
        Path shapes = fixtureClasses.resolve("Shapes.java");
        Files.writeString(shapes, """
          package fixture;
          import java.io.Serializable;
          import java.util.*;
          public class Shapes<T extends Comparable<? super T> & Serializable, R extends Shapes<T, R>> {
              public static int count;
              public Map<? extends T, List<? super T>> map;
              public int[][] grid;
              public Shapes(T seed) {}
              public <E extends Enum<E>> EnumSet<E> all(Class<E> type, String... names) { return null; }
              public R self() { return null; }
              public List<?> any() { return null; }
              public void run() {}
          }
          """);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
          "-d", fixtureClasses.toString(), shapes.toString());
        assertThat(exit).isZero();
    }

    @Test
    void signatures() throws Exception {
        String t = "Generic{T extends java.lang.Comparable<Generic{? super Generic{T}}> & java.io.Serializable}";
        String r = "Generic{R extends fixture.Shapes<" + t + ", Generic{R}>}";
        String e = "Generic{E extends java.lang.Enum<Generic{E}>}";
        String map = "java.util.Map<Generic{? extends " + t + "}, java.util.List<Generic{? super " + t + "}>>";
        String constructor = "fixture.Shapes{name=<constructor>,return=fixture.Shapes,parameters=[" + t + "]}";
        String all = "fixture.Shapes{name=all,return=java.util.EnumSet<" + e + ">,parameters=[java.lang.Class<" + e + ">,java.lang.String[]]}";
        String any = "fixture.Shapes{name=any,return=java.util.List<Generic{?}>,parameters=[]}";
        String run = "fixture.Shapes{name=run,return=void,parameters=[]}";
        String self = "fixture.Shapes{name=self,return=" + r + ",parameters=[]}";
        assertThat(probe("signatures", parser(), "fixture.Shapes")).isEqualTo(List.of(
          "array int[][]",
          "captured " + map,
          "class fixture.Shapes",
          "erased fixture.Shapes",
          "field fixture.Shapes{name=count,type=int}",
          "field fixture.Shapes{name=grid,type=int[][]}",
          "field fixture.Shapes{name=map,type=" + map + "}",
          "invoked " + constructor,
          "invoked " + all,
          "invoked " + any,
          "invoked " + run,
          "invoked " + self,
          "method " + constructor,
          "method " + all,
          "method " + any,
          "method " + run,
          "method " + self,
          "parameter " + constructor + "{name=arg0,type=" + t + "}",
          "parameter " + all + "{name=arg0,type=java.lang.Class<" + e + ">}",
          "parameter " + all + "{name=arg1,type=java.lang.String[]}",
          "type fixture.Shapes<" + t + ", " + r + ">"
        ));
    }

    @Test
    void classSignatureIsDecodedOnce() throws Exception {
        assertThat(probe("reusesClassSignature", parser(), "fixture.Shapes")).isEqualTo(true);
    }

    @Test
    void sourceParameterSignatureCarriesDeclarationPosition() throws Exception {
        String take = "A{name=take,return=void,parameters=[int]}";
        String constructor = "A{name=<constructor>,return=A,parameters=[]}";
        String shapes = "fixture.Shapes<Generic{?}, Generic{?}>";
        assertThat(probe("signatures", parser(), "A")).isEqualTo(List.of(
          "captured fixture.Shapes<Generic{? extends java.lang.Comparable<Generic{? super Generic{?}}> & java.io.Serializable}, Generic{? extends fixture.Shapes<Generic{?}, Generic{?}>}>",
          "class A",
          "erased A",
          "field A{name=shapes,type=" + shapes + "}",
          "invoked " + constructor,
          "invoked " + take,
          "method " + constructor,
          "method " + take,
          "parameter " + take + "{name=width,type=int,pos=53}",
          "type A"
        ));
    }

    private static JavaParser parser() {
        JavaParser parser = Java17Parser.builder().classpath(List.of(fixtureClasses)).build();
        parser.parse(new InMemoryExecutionContext(), "class A { fixture.Shapes<?, ?> shapes; void take(int width) {} }").findFirst().orElseThrow();
        return parser;
    }

    private static Object probe(String method, JavaParser parser, String fqn) throws Exception {
        Field delegateField = Java17Parser.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(parser);
        Class<?> probe = delegate.getClass().getClassLoader().loadClass("org.openrewrite.java.isolated.TypeSignatureProbe");
        return probe.getMethod(method, Object.class, String.class).invoke(null, delegate, fqn);
    }
}
