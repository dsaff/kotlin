/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.*
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1DescFunctionLikeSymbolPointer
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRevivingSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.name.ClassId

internal class Kt1DescConstructorSymbol(
    override val descriptor: ConstructorDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtConstructorSymbol(), Kt1DescMemberSymbol<ConstructorDescriptor> {
    override val isPrimary: Boolean
        get() = withValidityAssertion { descriptor.isPrimary }

    override val containingClassIdIfNonLocal: ClassId?
        get() = withValidityAssertion { descriptor.constructedClass.classId }

    override val valueParameters: List<KtValueParameterSymbol>
        get() = withValidityAssertion { descriptor.valueParameters.map { Kt1DescValueParameterSymbol(it, analysisSession) } }

    override val hasStableParameterNames: Boolean
        get() = withValidityAssertion { descriptor.ktHasStableParameterNames }

    override val annotatedType: KtTypeAndAnnotations
        get() = withValidityAssertion { descriptor.returnType.toKtTypeAndAnnotations(analysisSession) }

    override val dispatchType: KtType?
        get() = withValidityAssertion {
            val containingClass = descriptor.constructedClass.containingDeclaration as? ClassDescriptor ?: return null
            return containingClass.defaultType.toKtType(analysisSession)
        }

    override val typeParameters: List<KtTypeParameterSymbol>
        get() = withValidityAssertion { descriptor.typeParameters.map { Kt1DescTypeParameterSymbol(it, analysisSession) } }

    override fun createPointer(): KtSymbolPointer<KtConstructorSymbol> = withValidityAssertion {
        val pointerByPsi = KtPsiBasedSymbolPointer.createForSymbolFromSource(this)
        if (pointerByPsi != null) {
            return pointerByPsi
        }

        val callableId = descriptor.callableId
        if (callableId != null && !callableId.isLocal) {
            val signature = descriptor.getSymbolPointerSignature(analysisSession)
            return Kt1DescFunctionLikeSymbolPointer(callableId, signature)
        }

        return Kt1NeverRevivingSymbolPointer()
    }
}