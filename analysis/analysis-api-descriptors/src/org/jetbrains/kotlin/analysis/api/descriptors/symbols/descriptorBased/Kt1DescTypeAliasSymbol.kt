/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.Kt1DescSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.classId
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.ktVisibility
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtType
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class Kt1DescTypeAliasSymbol(
    override val descriptor: TypeAliasDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtTypeAliasSymbol(), Kt1DescSymbol<TypeAliasDescriptor> {
    override val name: Name
        get() = withValidityAssertion { descriptor.name }

    override val typeParameters: List<KtTypeParameterSymbol>
        get() = withValidityAssertion { descriptor.declaredTypeParameters.map { Kt1DescTypeParameterSymbol(it, analysisSession) } }

    override val visibility: Visibility
        get() = withValidityAssertion { descriptor.ktVisibility }

    override val expandedType: KtType
        get() = withValidityAssertion { descriptor.expandedType.toKtType(analysisSession) }

    override val classIdIfNonLocal: ClassId?
        get() = withValidityAssertion { descriptor.classId }

    override fun createPointer(): KtSymbolPointer<KtTypeAliasSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}