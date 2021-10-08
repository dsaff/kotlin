/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.ktVisibility
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtType
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.base.Kt1PsiSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.base.createErrorType
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.base.ktVisibility
import org.jetbrains.kotlin.analysis.api.descriptors.utils.cached
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.resolve.BindingContext

internal class Kt1PsiTypeAliasSymbol(
    override val psi: KtTypeAlias,
    override val analysisSession: Kt1AnalysisSession
) : KtTypeAliasSymbol(), Kt1PsiSymbol<KtTypeAlias, TypeAliasDescriptor> {
    override val descriptor: TypeAliasDescriptor? by cached {
        val bindingContext = analysisSession.analyze(psi)
        bindingContext[BindingContext.TYPE_ALIAS, psi]
    }

    override val name: Name
        get() = withValidityAssertion { psi.nameAsSafeName }

    override val typeParameters: List<KtTypeParameterSymbol>
        get() = withValidityAssertion { psi.typeParameters.map { Kt1PsiTypeParameterSymbol(it, analysisSession) } }

    override val visibility: Visibility
        get() = withValidityAssertion { psi.ktVisibility ?: descriptor?.ktVisibility ?: Visibilities.Public }

    override val expandedType: KtType
        get() = withValidityAssertion { descriptor?.expandedType?.toKtType(analysisSession) ?: createErrorType() }

    override val classIdIfNonLocal: ClassId?
        get() = withValidityAssertion { psi.getClassId() }

    override fun createPointer(): KtSymbolPointer<KtTypeAliasSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}