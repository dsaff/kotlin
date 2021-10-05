/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.components

import org.jetbrains.kotlin.analysis.api.components.KtSubtypingComponent
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.types.base.Kt1Type
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion

internal class Kt1SubtypingComponent(override val analysisSession: Kt1AnalysisSession) : KtSubtypingComponent() {
    override val token: ValidityToken
        get() = analysisSession.token

    override fun isEqualTo(first: KtType, second: KtType): Boolean = withValidityAssertion {
        require(first is Kt1Type)
        require(second is Kt1Type)
        return first.type == second.type
    }

    override fun isSubTypeOf(subType: KtType, superType: KtType): Boolean = withValidityAssertion {
        require(subType is Kt1Type)
        require(superType is Kt1Type)
        val typeChecker = analysisSession.resolveSession.kotlinTypeCheckerOfOwnerModule
        return typeChecker.isSubtypeOf(subType.type, superType.type)
    }
}