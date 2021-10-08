/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.Kt1DescSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolKind
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.name.Name

internal class Kt1DescLocalVariableSymbol(
    override val descriptor: LocalVariableDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtLocalVariableSymbol(), Kt1DescSymbol<LocalVariableDescriptor> {
    override val name: Name
        get() = withValidityAssertion { descriptor.name }

    override val isVal: Boolean
        get() = withValidityAssertion { !descriptor.isVar }

    override val annotatedType: KtTypeAndAnnotations
        get() = withValidityAssertion { descriptor.type.toKtTypeAndAnnotations(analysisSession) }

    override val symbolKind: KtSymbolKind
        get() = withValidityAssertion { KtSymbolKind.LOCAL }

    override fun createPointer(): KtSymbolPointer<KtLocalVariableSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}