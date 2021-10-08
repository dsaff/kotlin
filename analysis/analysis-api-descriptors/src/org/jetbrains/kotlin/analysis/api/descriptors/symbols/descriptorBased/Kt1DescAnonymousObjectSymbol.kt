/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.Kt1DescMemberSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.ClassDescriptor

internal class Kt1DescAnonymousObjectSymbol(
    override val descriptor: ClassDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtAnonymousObjectSymbol(), Kt1DescMemberSymbol<ClassDescriptor> {
    override val superTypes: List<KtTypeAndAnnotations>
        get() = withValidityAssertion { descriptor.typeConstructor.supertypes.map { it.toKtTypeAndAnnotations(analysisSession) } }

    override fun createPointer(): KtSymbolPointer<KtAnonymousObjectSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}