/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.references

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.references.base.Kt1Reference
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.idea.references.KtForLoopInReference
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.resolve.BindingContext

internal class Kt1ForLoopInReference(element: KtForExpression) : KtForLoopInReference(element), Kt1Reference {
    override fun KtAnalysisSession.resolveToSymbols(): Collection<KtSymbol> {
        check(this is Kt1AnalysisSession)

        val loopRange = element.loopRange ?: return emptyList()
        val bindingContext = analyze(loopRange)

        return listOf(
            BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL,
            BindingContext.LOOP_RANGE_HAS_NEXT_RESOLVED_CALL,
            BindingContext.LOOP_RANGE_NEXT_RESOLVED_CALL
        ).mapNotNull { slice -> bindingContext[slice, loopRange]?.resultingDescriptor?.toKtCallableSymbol(this) }
    }
}