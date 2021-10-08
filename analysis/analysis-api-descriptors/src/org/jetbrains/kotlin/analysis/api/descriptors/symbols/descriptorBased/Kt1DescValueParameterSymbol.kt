/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.Kt1DescSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.resolve.calls.components.hasDefaultValue
import org.jetbrains.kotlin.resolve.calls.components.isVararg

internal class Kt1DescValueParameterSymbol(
    override val descriptor: ValueParameterDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtValueParameterSymbol(), Kt1DescSymbol<ValueParameterDescriptor> {
    override val name: Name
        get() = withValidityAssertion {
            return when (val name = descriptor.name) {
                SpecialNames.IMPLICIT_SET_PARAMETER -> Name.identifier("value")
                else -> name
            }
        }

    override val hasDefaultValue: Boolean
        get() = withValidityAssertion { descriptor.hasDefaultValue() }

    override val isVararg: Boolean
        get() = withValidityAssertion { descriptor.isVararg }

    override val annotatedType: KtTypeAndAnnotations
        get() = withValidityAssertion {
            return (descriptor.varargElementType ?: descriptor.type).toKtTypeAndAnnotations(analysisSession)
        }

    override fun createPointer(): KtSymbolPointer<KtValueParameterSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}