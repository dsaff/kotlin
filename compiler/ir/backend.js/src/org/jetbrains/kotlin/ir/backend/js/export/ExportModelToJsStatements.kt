/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.export

import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsAstUtils
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.defineProperty
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.jsAssignment
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.prototypeOf
import org.jetbrains.kotlin.ir.backend.js.utils.IrNamer
import org.jetbrains.kotlin.ir.backend.js.utils.emptyScope
import org.jetbrains.kotlin.ir.backend.js.utils.getJsNameOrKotlinName
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.js.backend.ast.*

class ExportModelToJsStatements(
    private val namer: IrNamer,
    private val declareNewNamespace: (String) -> String
) {
    private val namespaceToRefMap = mutableMapOf<String, JsNameRef>()

    fun generateModuleExport(module: ExportedModule, internalModuleName: JsName): List<JsStatement> {
        return module.declarations.flatMap { generateDeclarationExport(it, JsNameRef(internalModuleName)) }
    }

    fun generateDeclarationExport(declaration: ExportedDeclaration, namespace: JsNameRef?): List<JsStatement> {
        return when (declaration) {
            is ExportedNamespace -> {
                require(namespace != null) { "Only namespaced namespaces are allowed" }
                val statements = mutableListOf<JsStatement>()
                val elements = declaration.name.split(".")
                var currentNamespace = ""
                var currentRef: JsNameRef = namespace
                for (element in elements) {
                    val newNamespace = "$currentNamespace$$element"
                    val newNameSpaceRef = namespaceToRefMap.getOrPut(newNamespace) {
                        val varName = JsName(declareNewNamespace(newNamespace), false)
                        val namespaceRef = JsNameRef(element, currentRef)
                        statements += JsVars(
                            JsVars.JsVar(
                                varName,
                                JsAstUtils.or(
                                    namespaceRef,
                                    jsAssignment(
                                        namespaceRef,
                                        JsObjectLiteral()
                                    )
                                )
                            )
                        )
                        JsNameRef(varName)
                    }
                    currentRef = newNameSpaceRef
                    currentNamespace = newNamespace
                }
                statements + declaration.declarations.flatMap { generateDeclarationExport(it, currentRef) }
            }

            is ExportedFunction -> {
                val name = namer.getNameForStaticDeclaration(declaration.ir)
                if (namespace == null) {
                    listOf(JsExport(name, alias = JsName(declaration.name, false)))
                } else {
                    listOf(
                        jsAssignment(
                            JsNameRef(declaration.name, namespace),
                            JsNameRef(name)
                        ).makeStmt()
                    )
                }
            }

            is ExportedConstructor -> emptyList()
            is ExportedConstructSignature -> emptyList()

            is ExportedProperty -> {
                require(namespace != null) { "Only namespaced properties are allowed" }
                val getter = declaration.irGetter?.let { JsNameRef(namer.getNameForStaticDeclaration(it)) }
                val setter = declaration.irSetter?.let { JsNameRef(namer.getNameForStaticDeclaration(it)) }
                listOf(defineProperty(namespace, declaration.name, getter, setter).makeStmt())
            }

            is ErrorDeclaration -> emptyList()

            is ExportedClass -> {
                if (declaration.isInterface) return emptyList()
                val newNameSpace = JsNameRef(declaration.name, namespace)
                val name = namer.getNameForStaticDeclaration(declaration.ir)
                val klassExport =
                    if (namespace == null) {
                        JsExport(name, alias = JsName(declaration.name, false))
                    } else {
                        jsAssignment(
                            newNameSpace,
                            JsNameRef(name)
                        ).makeStmt()
                    }

                // These are only used when exporting secondary constructors annotated with @JsName
                val staticFunctions = declaration.members.filter { it is ExportedFunction && it.isStatic }

                // Nested objects are exported as static properties
                val staticProperties = declaration.members.mapNotNull {
                    (it as? ExportedProperty)?.takeIf { it.isStatic }
                }

                val innerClassesAssignments = declaration.nestedClasses
                    .filter { it.ir.isInner }
                    .map { it.generateInnerClassAssignmentFor(namespace, declaration) }

                val staticsExport = (staticFunctions + staticProperties + declaration.nestedClasses)
                    .flatMap { generateDeclarationExport(it, newNameSpace) }

                listOf(klassExport) + staticsExport + innerClassesAssignments
            }
        }
    }

    private fun ExportedClass.generateInnerClassAssignmentFor(namespace: JsNameRef?, klass: ExportedClass): JsStatement {
        val bindConstructor = JsName("__bind_constructor_", false)
        val companionObject = ir.companionObject()
        val outerClassRef = JsNameRef(namer.getNameForStaticDeclaration(klass.ir), namespace)
        val innerClassRef = namer.getNameForStaticDeclaration(ir).makeRef()

        val blockStatements = mutableListOf<JsStatement>(
            JsVars(
                JsVars.JsVar(
                    bindConstructor, JsInvocation(
                        JsNameRef("bind", innerClassRef),
                        JsNullLiteral(),
                        JsThisRef()
                    )
                )
            )
        )

        if (companionObject != null) {
            val companionName = companionObject.getJsNameOrKotlinName().identifier
            blockStatements.add(
                jsAssignment(
                    JsNameRef(companionName, bindConstructor.makeRef()),
                    JsNameRef(companionName, innerClassRef),
                ).makeStmt()
            )
        }

        blockStatements.add(JsReturn(bindConstructor.makeRef()))

        return defineProperty(
            prototypeOf(outerClassRef),
            innerClassRef.ident,
            JsFunction(
                emptyScope,
                JsBlock(*blockStatements.toTypedArray()),
                "inner class '${innerClassRef.ident}' getter"
            ),
            null
        ).makeStmt()
    }
}
