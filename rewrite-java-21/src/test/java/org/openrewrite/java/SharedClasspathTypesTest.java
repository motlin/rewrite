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
package org.openrewrite.java;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.tree.ParseError;
import org.openrewrite.SourceFile;
import org.openrewrite.java.internal.DefaultJavaTypeFactory;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.internal.SharedClasspathTypes;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

class SharedClasspathTypesTest {

    private static final Collection<Path> ASSERTJ = JavaParser.dependenciesFromClasspath("assertj-core");

    @BeforeEach
    void clearSharedTypes() {
        SharedClasspathTypes.clear();
    }

    @Test
    void reusesClasspathTypesAcrossParsers() {
        JavaType first = fieldType(parse(true, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");
        JavaType second = fieldType(parse(true, "class B { org.assertj.core.data.Offset<Double> o; }"), "B", "o");

        assertThat(second).isSameAs(first);
    }

    @Test
    void doesNotShareWhenNotEnabled() {
        JavaType first = fieldType(parse(false, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");
        JavaType second = fieldType(parse(false, "class B { org.assertj.core.data.Offset<Double> o; }"), "B", "o");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void doesNotShareWithAConfiguredTypeFactory() {
        JavaType first = fieldType(parse(true, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");
        JavaType second = fieldType(JavaParser.fromJavaVersion()
                .classpath(ASSERTJ)
                .shareClasspathTypes(true)
                .typeFactory(new DefaultJavaTypeFactory(new JavaTypeCache()))
                .build()
                .parse(new InMemoryExecutionContext(t -> {
                }), "class B { org.assertj.core.data.Offset<Double> o; }")
                .collect(toList()), "B", "o");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void sourceClassesWithTheSameNameDoNotLeakBetweenParses() {
        parse(true, "class A { void foo() {} }", "class B { java.util.List<A> l; }");
        List<SourceFile> second = parse(true, "class A { void bar() {} }", "class B { java.util.List<A> l; }");

        assertThat(methodNames(classType(second, "A"))).containsExactly("bar");
        JavaType.Parameterized list = (JavaType.Parameterized) fieldType(second, "B", "l");
        assertThat(methodNames((JavaType.FullyQualified) list.getTypeParameters().get(0))).containsExactly("bar");
    }

    @Test
    void sourceClassShadowingAClasspathClassIsNotReplacedBySharedType() {
        parse(true, "class A { org.assertj.core.data.Offset<Double> o; }");
        List<SourceFile> second = parse(true,
                "package org.assertj.core.data; public class Offset<T> { public void mine() {} }",
                "class B { org.assertj.core.data.Offset<Double> o; }");

        JavaType.Parameterized offset = (JavaType.Parameterized) fieldType(second, "B", "o");
        assertThat(methodNames(offset.getType())).containsExactly("mine");
    }

    @Test
    void missingClassLaterDeclaredInSourceIsNotReplacedBySharedType() {
        parse(true, "class A { Missing m; java.util.List<Missing> l; }");
        List<SourceFile> second = parse(true,
                "class Missing { void z() {} }",
                "class B { java.util.List<Missing> l; }");

        JavaType.Parameterized list = (JavaType.Parameterized) fieldType(second, "B", "l");
        assertThat(methodNames((JavaType.FullyQualified) list.getTypeParameters().get(0))).containsExactly("z");
    }

    @Test
    void sharedTypesMatchFreshTypes() {
        String source = "class A { void test(double d) { org.assertj.core.api.Assertions.assertThat(d).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.0)); } }";
        List<String> fresh = describe(fieldOrMethodTypes(parse(false, source)));
        parse(true, "class Warm { org.assertj.core.api.AbstractDoubleAssert<?> a; }");
        List<String> shared = describe(fieldOrMethodTypes(parse(true, source)));

        assertThat(shared).isEqualTo(fresh);
    }

    @Test
    void classRecompiledIntoADirectoryIsNotServedStale(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("classes"));
        compile(dir, "package p.q; public class Foo { public void one() {} }");
        parse(true, dir, "class A { p.q.Foo f; }");

        compile(dir, "package p.q; public class Foo { public void two() {} }");
        List<SourceFile> second = parse(true, dir, "class A { p.q.Foo f; }");

        assertThat(methodNames((JavaType.FullyQualified) fieldType(second, "A", "f"))).containsExactly("two");
    }

    @Test
    void sharesClassesReadFromJars(@TempDir Path tmp) throws IOException {
        Path jar = jar(tmp, "package p.q; public class Foo { public void one() {} }");
        JavaType first = fieldType(parse(true, jar, "class A { p.q.Foo f; }"), "A", "f");
        JavaType second = fieldType(parse(true, jar, "class B { p.q.Foo f; }"), "B", "f");

        assertThat(second).isSameAs(first);
    }

    @Test
    void directoryClassShadowingASharedJarClassIsNotReplacedBySharedType(@TempDir Path tmp) throws IOException {
        Path jar = jar(tmp, "package p.q; public class Foo { public void one() {} }");
        Path dir = Files.createDirectory(tmp.resolve("classes"));
        parse(true, List.of(dir, jar), "class A { p.q.Foo f; }");

        compile(dir, "package p.q; public class Foo { public void two() {} }");
        List<SourceFile> second = parse(true, List.of(dir, jar), "class A { p.q.Foo f; }");

        assertThat(methodNames((JavaType.FullyQualified) fieldType(second, "A", "f"))).containsExactly("two");
    }

    @Test
    void doesNotShareClassesFromClassBytes(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("classes"));
        compile(dir, "package p.q; public class Foo { public void one() {} }");
        byte[] foo = Files.readAllBytes(dir.resolve("p/q/Foo.class"));

        JavaType first = fieldType(parseWithClassBytes(foo, "class A { p.q.Foo f; }"), "A", "f");
        JavaType second = fieldType(parseWithClassBytes(foo, "class B { p.q.Foo f; }"), "B", "f");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void changingASharedTypeInPlaceDoesNotLeakIntoLaterParses() {
        JavaType.Parameterized first = (JavaType.Parameterized) fieldType(parse(true, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");

        JavaType changed = first.unsafeSet(first.getType(), List.of(JavaType.Primitive.String));
        JavaType.Parameterized second = (JavaType.Parameterized) fieldType(parse(true, "class B { org.assertj.core.data.Offset<Double> o; }"), "B", "o");

        assertThat(changed.toString()).isEqualTo("org.assertj.core.data.Offset<String>");
        assertThat(second.toString()).isEqualTo("org.assertj.core.data.Offset<java.lang.Double>");
        assertThat(first.toString()).isEqualTo("org.assertj.core.data.Offset<java.lang.Double>");
    }

    @Test
    void changingASharedClassInPlaceDoesNotLeakIntoLaterParses() {
        JavaType.Parameterized first = (JavaType.Parameterized) fieldType(parse(true, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");
        JavaType.Class offset = (JavaType.Class) first.getType();

        JavaType.Class changed = offset.unsafeSet(offset.getTypeParameters(), offset.getSupertype(), offset.getOwningClass(),
                offset.getAnnotations(), offset.getInterfaces(), offset.getMembers(), List.of());
        JavaType.Parameterized second = (JavaType.Parameterized) fieldType(parse(true, "class B { org.assertj.core.data.Offset<Double> o; }"), "B", "o");

        assertThat(changed.getMethods()).isEmpty();
        assertThat(methodNames(second.getType())).contains("offset", "strictOffset");
        assertThat(methodNames(offset)).contains("offset", "strictOffset");
    }

    @Test
    void visitorChangingASharedTypeGetsACopy() {
        JavaType.Parameterized first = (JavaType.Parameterized) fieldType(parse(true, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");

        JavaType visited = new JavaTypeVisitor<Integer>() {
            @Override
            public JavaType visitClass(JavaType.Class aClass, Integer p) {
                return "java.lang.Double".equals(aClass.getFullyQualifiedName()) ? JavaType.ShallowClass.build("java.lang.Long") : aClass;
            }
        }.visit(first, 0);
        JavaType.Parameterized second = (JavaType.Parameterized) fieldType(parse(true, "class B { org.assertj.core.data.Offset<Double> o; }"), "B", "o");

        assertThat(visited.toString()).isEqualTo("org.assertj.core.data.Offset<java.lang.Long>");
        assertThat(second.toString()).isEqualTo("org.assertj.core.data.Offset<java.lang.Double>");
    }

    @Test
    void visitorThatChangesNothingKeepsTheSharedType() {
        JavaType.Parameterized first = (JavaType.Parameterized) fieldType(parse(true, "class A { org.assertj.core.data.Offset<Double> o; }"), "A", "o");

        JavaType visited = new JavaTypeVisitor<Integer>() {
            @Override
            public JavaType visitClass(JavaType.Class aClass, Integer p) {
                return aClass;
            }
        }.visit(first, 0);

        assertThat(visited).isSameAs(first);
    }

    @Test
    void siblingParserDoesNotShareTypesHalfBuiltByAFailedMapping() {
        AtomicBoolean fail = new AtomicBoolean(true);
        JavaTypeCache failOnceInsideOffset = new JavaTypeCache() {
            @Override
            public void put(String signature, Object o) {
                if (signature.startsWith("org.assertj.core.data.Offset{") && fail.getAndSet(false)) {
                    throw new IllegalStateException("simulated type mapping failure");
                }
                super.put(signature, o);
            }
        };
        JavaParser.Builder<? extends JavaParser, ?> builder = JavaParser.fromJavaVersion()
                .classpath(ASSERTJ)
                .shareClasspathTypes(true)
                .typeCache(failOnceInsideOffset);

        List<SourceFile> failed = builder.build().parse(new InMemoryExecutionContext(t -> {
        }), "class A { org.assertj.core.data.Offset<Double> o; }").collect(toList());
        assertThat(failed).singleElement().isInstanceOf(ParseError.class);

        builder.build().parse(new InMemoryExecutionContext(t -> {
        }), "class B { org.assertj.core.data.Offset<Double> o; }").collect(toList());
        JavaType.Parameterized fresh = (JavaType.Parameterized) fieldType(parse(true, "class C { org.assertj.core.data.Offset<Double> o; }"), "C", "o");

        assertThat(methodNames(fresh.getType())).contains("offset", "strictOffset");
    }

    private static List<SourceFile> parseWithClassBytes(byte[] classBytes, String... sources) {
        return JavaParser.fromJavaVersion()
                .classpath(ASSERTJ)
                .classpath(classBytes)
                .shareClasspathTypes(true)
                .build()
                .parse(new InMemoryExecutionContext(t -> {
                }), sources)
                .collect(toList());
    }

    private static void compile(Path outputDir, String source) throws IOException {
        Path src = Files.createTempDirectory("shared-classpath-types-src");
        Path file = src.resolve("Foo.java");
        Files.writeString(file, source);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int status = javac.run(null, null, null, "-d", outputDir.toString(), file.toString());
        assertThat(status).isZero();
    }

    private static Path jar(Path tmp, String source) throws IOException {
        Path classes = Files.createTempDirectory(tmp, "jar-classes");
        compile(classes, source);
        Path jar = tmp.resolve("foo.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("p/q/Foo.class"));
            jarOut.write(Files.readAllBytes(classes.resolve("p/q/Foo.class")));
            jarOut.closeEntry();
        }
        return jar;
    }

    private static List<SourceFile> parse(boolean share, Path classpathEntry, String... sources) {
        return parse(share, List.of(classpathEntry), sources);
    }

    private static List<SourceFile> parse(boolean share, Collection<Path> classpath, String... sources) {
        return JavaParser.fromJavaVersion()
                .classpath(classpath)
                .shareClasspathTypes(share)
                .build()
                .parse(new InMemoryExecutionContext(t -> {
                }), sources)
                .collect(toList());
    }

    private static List<SourceFile> parse(boolean share, String... sources) {
        return JavaParser.fromJavaVersion()
                .classpath(ASSERTJ)
                .shareClasspathTypes(share)
                .build()
                .parse(new InMemoryExecutionContext(t -> {
                }), sources)
                .collect(toList());
    }

    private static JavaType.FullyQualified classType(List<SourceFile> sources, String name) {
        for (SourceFile s : sources) {
            for (J.ClassDeclaration c : ((J.CompilationUnit) s).getClasses()) {
                if (c.getSimpleName().equals(name)) {
                    return c.getType();
                }
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static JavaType fieldType(List<SourceFile> sources, String className, String field) {
        for (JavaType.Variable v : classType(sources, className).getMembers()) {
            if (v.getName().equals(field)) {
                return v.getType();
            }
        }
        throw new IllegalArgumentException(field);
    }

    private static List<String> methodNames(JavaType.FullyQualified type) {
        return type.getMethods().stream().map(JavaType.Method::getName).filter(n -> !"<constructor>".equals(n)).collect(toList());
    }

    private static List<JavaType> fieldOrMethodTypes(List<SourceFile> sources) {
        List<JavaType> types = new ArrayList<>();
        new JavaIsoVisitor<List<JavaType>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, List<JavaType> acc) {
                if (method.getMethodType() != null) {
                    acc.add(method.getMethodType());
                }
                return super.visitMethodInvocation(method, acc);
            }
        }.visit(sources.get(0), types);
        assertThat(types).isNotEmpty();
        return types;
    }

    /**
     * Describes each method and the full surface of its declaring type, return type, and parameter types, so two
     * structurally different type graphs give different descriptions.
     */
    private static List<String> describe(List<JavaType> types) {
        List<String> out = new ArrayList<>();
        for (JavaType t : types) {
            JavaType.Method m = (JavaType.Method) t;
            out.add(m.toString());
            out.add(describeClass(m.getDeclaringType()));
            out.add(describeClass(TypeUtils.asFullyQualified(m.getReturnType())));
            for (JavaType p : m.getParameterTypes()) {
                out.add(describeClass(TypeUtils.asFullyQualified(p)));
            }
        }
        return out;
    }

    private static String describeClass(JavaType.FullyQualified fq) {
        if (fq == null) {
            return "null";
        }
        Set<String> methods = new TreeSet<>();
        for (JavaType.Method m : fq.getMethods()) {
            methods.add(m + " -> " + m.getReturnType() + " " + m.getParameterTypes() + " " + m.getAnnotations() + " " + m.getFlags());
        }
        Set<String> fields = new TreeSet<>();
        for (JavaType.Variable v : fq.getMembers()) {
            fields.add(v + " " + v.getType() + " " + v.getFlags());
        }
        return fq + " super=" + fq.getSupertype() + " interfaces=" + fq.getInterfaces() +
               " annotations=" + fq.getAnnotations() + " flags=" + fq.getFlags() + " methods=" + methods + " fields=" + fields;
    }
}
