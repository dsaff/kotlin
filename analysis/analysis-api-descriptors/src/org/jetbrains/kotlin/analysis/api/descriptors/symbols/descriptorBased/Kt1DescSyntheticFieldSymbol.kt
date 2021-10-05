/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.base.Kt1Symbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1DescSyntheticFieldSymbolPointer
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRevivingSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtBackingFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtPropertyAccessorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtVariableLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.SyntheticFieldDescriptor
import org.jetbrains.kotlin.load.kotlin.toSourceElement
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.createSmartPointer
import org.jetbrains.kotlin.resolve.source.getPsi

internal class Kt1DescSyntheticFieldSymbol(
    private val descriptor: SyntheticFieldDescriptor,
    override val analysisSession: Kt1AnalysisSession
) : KtBackingFieldSymbol(), Kt1Symbol {
    override val owningProperty: KtKotlinPropertySymbol
        get() = withValidityAssertion {
            val kotlinProperty = descriptor.propertyDescriptor as PropertyDescriptorImpl
            Kt1DescKotlinPropertySymbol(kotlinProperty, analysisSession)
        }

    override val annotatedType: KtTypeAndAnnotations
        get() = withValidityAssertion { descriptor.propertyDescriptor.type.toKtTypeAndAnnotations(analysisSession) }

    override fun createPointer(): KtSymbolPointer<KtVariableLikeSymbol> = withValidityAssertion {
        val accessorPsi = descriptor.containingDeclaration.toSourceElement.getPsi()
        if (accessorPsi is KtPropertyAccessor) {
            val accessorPointer = KtPsiBasedSymbolPointer<KtPropertyAccessorSymbol>(accessorPsi.createSmartPointer())
            return Kt1DescSyntheticFieldSymbolPointer(accessorPointer)
        }

        return Kt1NeverRevivingSymbolPointer()
    }
}