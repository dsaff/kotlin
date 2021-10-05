/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.references.base

import org.jetbrains.kotlin.analysis.api.descriptors.references.*
import org.jetbrains.kotlin.idea.references.KotlinPsiReferenceRegistrar
import org.jetbrains.kotlin.idea.references.KotlinReferenceProviderContributor

class Kt1KotlinReferenceProviderContributor : KotlinReferenceProviderContributor {
    override fun registerReferenceProviders(registrar: KotlinPsiReferenceRegistrar) {
        with(registrar) {
            registerProvider(factory = ::Kt1SimpleNameReference)
            registerProvider(factory = ::Kt1ForLoopInReference)
            registerProvider(factory = ::Kt1InvokeFunctionReference)
            registerProvider(factory = ::Kt1PropertyDelegationMethodsReference)
            registerProvider(factory = ::Kt1DestructuringDeclarationEntry)
            registerProvider(factory = ::Kt1ArrayAccessReference)
            registerProvider(factory = ::Kt1ConstructorDelegationReference)
            registerProvider(factory = ::Kt1CollectionLiteralReference)
        }
    }
}