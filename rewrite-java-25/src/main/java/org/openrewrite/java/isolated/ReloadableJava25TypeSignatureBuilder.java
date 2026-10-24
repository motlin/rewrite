/*
 * Copyright 2025 the original author or authors.
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

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Position;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.JavaTypeSignatureBuilder;
import org.openrewrite.java.tree.JavaType;

import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeMirror;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds every signature by appending into one {@link StringBuilder}.
 */
class ReloadableJava25TypeSignatureBuilder implements JavaTypeSignatureBuilder {
    private static final String OBJECT = "java.lang.Object";

    @Nullable
    private Set<String> typeVariableNameStack;

    /**
     * Every public signature method starts over in this one buffer. None of them calls another while it is
     * building, since the work below them takes the buffer as an argument.
     */
    private final StringBuilder buffer = new StringBuilder(256);

    private StringBuilder buffer() {
        buffer.setLength(0);
        return buffer;
    }

    @Override
    public String signature(@Nullable Object t) {
        return signature((Type) t);
    }

    private String signature(@Nullable Type type) {
        if (type instanceof Type.ClassType && !(type instanceof Type.IntersectionClassType) &&
            (((Type.ClassType) type).typarams_field == null || ((Type.ClassType) type).typarams_field.length() == 0)) {
            return type.tsym.flatName().toString();
        } else if (type instanceof Type.JCPrimitiveType) {
            return primitiveSignature(type);
        }
        StringBuilder s = buffer();
        appendSignature(s, type);
        return s.toString();
    }

    private void appendSignature(StringBuilder s, @Nullable Type type) {
        if (type == null || type instanceof NullType) {
            s.append("{undefined}");
        } else if (type instanceof Type.IntersectionClassType) {
            appendIntersection(s, (Type.IntersectionClassType) type);
        } else if (type instanceof Type.ClassType) {
            if (((Type.ClassType) type).typarams_field != null && ((Type.ClassType) type).typarams_field.length() > 0) {
                appendParameterized(s, type);
            } else {
                appendClass(s, type);
            }
        } else if (type instanceof Type.TypeVar) { // includes CapturedType
            appendGeneric(s, (Type.TypeVar) type);
        } else if (type instanceof Type.JCPrimitiveType) {
            s.append(primitiveSignature(type));
        } else if (type instanceof Type.JCVoidType) {
            s.append("void");
        } else if (type instanceof Type.ArrayType) {
            appendArray(s, type);
        } else if (type instanceof Type.WildcardType) {
            Type.WildcardType wildcard = (Type.WildcardType) type;
            s.append("Generic{").append(wildcard.kind.toString());
            if (!type.isUnbound()) {
                appendSignature(s, wildcard.type);
            }
            s.append('}');
        } else if (type instanceof Type.JCNoType) {
            s.append("{none}");
        } else {
            throw new IllegalStateException("Unexpected type " + type.getClass().getName());
        }
    }

    @Override
    public String arraySignature(Object type) {
        StringBuilder s = buffer();
        appendArray(s, (Type) type);
        return s.toString();
    }

    private void appendArray(StringBuilder s, Type type) {
        Type elemType = type;
        int dimensions = 0;
        while (elemType instanceof Type.ArrayType) {
            dimensions++;
            elemType = ((Type.ArrayType) elemType).elemtype;
        }
        appendSignature(s, elemType);
        for (int i = 0; i < dimensions; i++) {
            s.append("[]");
        }
    }

    public String annotatedArraySignature(JCTree.JCAnnotatedType annotatedType) {
        JCTree tree = annotatedType;
        StringBuilder dimensions = new StringBuilder();
        while (tree instanceof JCTree.JCAnnotatedType || tree instanceof JCTree.JCArrayTypeTree) {
            if (tree instanceof JCTree.JCAnnotatedType) {
                appendAnnotations(dimensions, ((JCTree.JCAnnotatedType) tree).annotations);
                tree = ((JCTree.JCAnnotatedType) tree).getUnderlyingType();
            }
            if (tree instanceof JCTree.JCArrayTypeTree) {
                dimensions.append("[]");
                tree = ((JCTree.JCArrayTypeTree) tree).getType();
            }
        }
        StringBuilder s = buffer();
        appendSignature(s, tree.type);
        return s.append(dimensions).toString();
    }

    private void appendAnnotations(StringBuilder s, List<JCTree.JCAnnotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }
        s.append('[');
        boolean first = true;
        for (JCTree.JCAnnotation annotation : annotations) {
            if (!first) {
                s.append(',');
            }
            first = false;
            appendSignature(s, annotation.type);
        }
        s.append(']');
    }

    @Override
    public String classSignature(Object type) {
        if (type instanceof Type.JCVoidType) {
            return "void";
        } else if (type instanceof Type.JCPrimitiveType) {
            return primitiveSignature(type);
        } else if (type instanceof Type.JCNoType) {
            return "{undefined}";
        }

        return ((Type.ClassType) type).tsym.flatName().toString();
    }

    private void appendClass(StringBuilder s, Type type) {
        s.append(classSignature(type));
    }

    @Override
    public String genericSignature(Object type) {
        StringBuilder s = buffer();
        appendGeneric(s, (Type.TypeVar) type);
        return s.toString();
    }

    private void appendGeneric(StringBuilder s, Type.TypeVar generic) {
        String name = generic instanceof Type.CapturedType ? "?" : generic.tsym.name.toString();

        if (typeVariableNameStack == null) {
            typeVariableNameStack = new HashSet<>();
        }

        s.append("Generic{").append(name);
        if (!typeVariableNameStack.add(name)) {
            s.append('}');
            return;
        }

        int boundsStart = s.length();
        s.append(" extends ");
        boolean anyBound = false;
        Type upperBound = generic.getUpperBound();
        if (upperBound instanceof Type.IntersectionClassType) {
            Type.IntersectionClassType intersectionBound = (Type.IntersectionClassType) upperBound;
            if (intersectionBound.supertype_field != null) {
                anyBound = appendBound(s, intersectionBound.supertype_field, anyBound, true);
            }
            for (Type bound : intersectionBound.interfaces_field) {
                anyBound = appendBound(s, bound, anyBound, false);
            }
        } else {
            anyBound = appendBound(s, upperBound, anyBound, true);
        }
        if (!anyBound) {
            s.setLength(boundsStart);
        }

        typeVariableNameStack.remove(name);
        s.append('}');
    }

    /**
     * @return whether any bound has been appended, including this one.
     */
    private boolean appendBound(StringBuilder s, Type bound, boolean anyBound, boolean omitObject) {
        int mark = s.length();
        if (anyBound) {
            s.append(" & ");
        }
        int boundStart = s.length();
        appendSignature(s, bound);
        if (omitObject && s.length() - boundStart == OBJECT.length() && s.indexOf(OBJECT, boundStart) == boundStart) {
            s.setLength(mark);
            return anyBound;
        }
        return true;
    }

    private void appendIntersection(StringBuilder s, Type.IntersectionClassType intersectionClassType) {
        boolean first = true;
        for (TypeMirror typeArg : intersectionClassType.getBounds()) {
            if (!first) {
                s.append(" & ");
            }
            first = false;
            appendSignature(s, (Type) typeArg);
        }
    }

    @Override
    public String parameterizedSignature(Object type) {
        StringBuilder s = buffer();
        appendParameterized(s, (Type) type);
        return s.toString();
    }

    private void appendParameterized(StringBuilder s, Type type) {
        appendClass(s, type);
        s.append('<');
        boolean first = true;
        for (Type tp : ((Type.ClassType) type).typarams_field) {
            if (!first) {
                s.append(", ");
            }
            first = false;
            appendSignature(s, tp);
        }
        s.append('>');
    }

    @Override
    public String primitiveSignature(Object type) {
        TypeTag tag = ((Type.JCPrimitiveType) type).getTag();
        switch (tag) {
            case BOOLEAN:
                return JavaType.Primitive.Boolean.getKeyword();
            case BYTE:
                return JavaType.Primitive.Byte.getKeyword();
            case CHAR:
                return JavaType.Primitive.Char.getKeyword();
            case DOUBLE:
                return JavaType.Primitive.Double.getKeyword();
            case FLOAT:
                return JavaType.Primitive.Float.getKeyword();
            case INT:
                return JavaType.Primitive.Int.getKeyword();
            case LONG:
                return JavaType.Primitive.Long.getKeyword();
            case SHORT:
                return JavaType.Primitive.Short.getKeyword();
            case VOID:
                return JavaType.Primitive.Void.getKeyword();
            case NONE:
                return JavaType.Primitive.None.getKeyword();
            case CLASS:
                return JavaType.Primitive.String.getKeyword();
            case BOT:
                return JavaType.Primitive.Null.getKeyword();
            default:
                throw new IllegalArgumentException("Unknown type tag " + tag);
        }
    }

    public String methodSignature(Type selectType, Symbol.MethodSymbol symbol) {
        StringBuilder s = buffer();
        String owner = classSignature(symbol.owner.type);
        s.append(owner);
        if (symbol.isConstructor()) {
            s.append("{name=<constructor>,return=").append(owner);
        } else {
            s.append("{name=").append(symbol.getSimpleName().toString()).append(",return=");
            appendSignature(s, selectType.getReturnType());
        }
        s.append(",parameters=");
        appendMethodArguments(s, selectType);
        return s.append('}').toString();
    }

    public String methodSignature(Symbol.MethodSymbol symbol) {
        StringBuilder s = buffer();
        appendMethod(s, symbol);
        return s.toString();
    }

    private void appendMethod(StringBuilder s, Symbol.MethodSymbol symbol) {
        String owner = classSignature(symbol.owner.type);
        s.append(owner);
        if (symbol.isConstructor()) {
            s.append("{name=<constructor>,return=").append(owner);
        } else {
            s.append("{name=").append(symbol.getSimpleName().toString()).append(",return=");
            if (symbol.isStaticOrInstanceInit()) {
                s.append("void");
            } else {
                appendSignature(s, symbol.getReturnType());
            }
        }
        s.append(",parameters=");
        appendMethodArguments(s, symbol);
        s.append('}');
    }

    private void appendMethodArguments(StringBuilder s, Symbol.MethodSymbol sym) {
        if (sym.isStaticOrInstanceInit()) {
            s.append("[]");
            return;
        }

        s.append('[');
        if (sym.type == null) {
            s.append("{undefined}");
        } else {
            boolean first = true;
            for (Symbol.VarSymbol parameter : sym.getParameters()) {
                if (!first) {
                    s.append(',');
                }
                first = false;
                appendSignature(s, parameter.type);
            }
        }
        s.append(']');
    }

    private void appendMethodArguments(StringBuilder s, Type selectType) {
        if (selectType instanceof Type.MethodType) {
            s.append('[');
            boolean first = true;
            for (Type argtype : ((Type.MethodType) selectType).argtypes) {
                if (argtype != null) {
                    if (!first) {
                        s.append(',');
                    }
                    first = false;
                    appendSignature(s, argtype);
                }
            }
            s.append(']');
        } else if (selectType instanceof Type.ForAll) {
            appendMethodArguments(s, ((Type.ForAll) selectType).qtype);
        } else if (selectType instanceof Type.JCNoType) {
            s.append("{undefined}");
        } else {
            throw new UnsupportedOperationException("Unexpected method type " + selectType.getClass().getName());
        }
    }

    public String variableSignature(Symbol symbol) {
        StringBuilder s = buffer();
        if (symbol.owner instanceof Symbol.MethodSymbol) {
            appendMethod(s, (Symbol.MethodSymbol) symbol.owner);
        } else {
            appendSignature(s, symbol.owner.type);
            int typeParameters = s.indexOf("<");
            if (typeParameters >= 0) {
                s.setLength(typeParameters);
            }
        }

        s.append("{name=").append(symbol.name.toString()).append(",type=");
        appendSignature(s, symbol.type);
        if (!(symbol.owner instanceof Symbol.ClassSymbol) && symbol instanceof Symbol.VarSymbol &&
            ((Symbol.VarSymbol) symbol).pos != Position.NOPOS) {
            s.append(",pos=").append(((Symbol.VarSymbol) symbol).pos);
        }
        return s.append('}').toString();
    }
}
