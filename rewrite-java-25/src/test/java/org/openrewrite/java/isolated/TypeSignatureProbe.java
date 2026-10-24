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

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Loaded through the parser's class loader, the only one that links against javac's internals.
public class TypeSignatureProbe {

    /**
     * @return every signature built for the class and its members, sorted.
     */
    public static List<String> signatures(Object parser, String fqn) throws ReflectiveOperationException {
        Context context = context(parser);
        Types types = Types.instance(context);
        Symbol.ClassSymbol sym = classSymbol(context, fqn);
        ReloadableJava25TypeSignatureBuilder builder = new ReloadableJava25TypeSignatureBuilder();

        List<String> signatures = new ArrayList<>();
        signatures.add("class " + builder.classSignature(sym.type));
        signatures.add("type " + builder.signature(sym.type));
        signatures.add("erased " + builder.signature(types.erasure(sym.type)));
        for (Symbol member : sym.members().getSymbols()) {
            if (member instanceof Symbol.MethodSymbol) {
                Symbol.MethodSymbol method = (Symbol.MethodSymbol) member;
                signatures.add("method " + builder.methodSignature(method));
                signatures.add("invoked " + builder.methodSignature(method.type, method));
                for (Symbol.VarSymbol parameter : method.params()) {
                    signatures.add("parameter " + builder.variableSignature(parameter));
                }
            } else if (member instanceof Symbol.VarSymbol) {
                signatures.add("field " + builder.variableSignature(member));
                if (member.type instanceof Type.ArrayType) {
                    signatures.add("array " + builder.arraySignature(member.type));
                }
                if (!member.type.getTypeArguments().isEmpty()) {
                    signatures.add("captured " + builder.signature(types.capture(member.type)));
                }
            }
        }
        Collections.sort(signatures);
        return signatures;
    }

    private static Context context(Object parser) throws ReflectiveOperationException {
        Field contextField = ReloadableJava25Parser.class.getDeclaredField("context");
        contextField.setAccessible(true);
        return (Context) contextField.get(parser);
    }

    private static Symbol.ClassSymbol classSymbol(Context context, String fqn) {
        Symtab syms = Symtab.instance(context);
        Symbol.ClassSymbol sym = syms.getClass(syms.unnamedModule, Names.instance(context).fromString(fqn));
        sym.complete();
        return sym;
    }
}
