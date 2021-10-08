/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.Kt1DescSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtType
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal class Kt1DescTypeParameterSymbol(
    override val descriptor: TypeParameterDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtTypeParameterSymbol(), Kt1DescSymbol<TypeParameterDescriptor> {
    override val name: Name
        get() = withValidityAssertion { descriptor.name }

    override val upperBounds: List<KtType>
        get() = withValidityAssertion { descriptor.upperBounds.map { it.toKtType(analysisSession) } }

    override val variance: Variance
        get() = withValidityAssertion { descriptor.variance }

    override val isReified: Boolean
        get() = withValidityAssertion { descriptor.isReified }

    override fun createPointer(): KtSymbolPointer<KtTypeParameterSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}