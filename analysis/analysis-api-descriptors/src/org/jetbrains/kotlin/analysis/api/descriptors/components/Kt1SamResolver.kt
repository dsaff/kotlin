/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.components

import org.jetbrains.kotlin.analysis.api.components.KtSamResolver
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.getSymbolDescriptor
import org.jetbrains.kotlin.analysis.api.symbols.KtClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.load.java.sam.JvmSamConversionOracle
import org.jetbrains.kotlin.resolve.sam.createSamConstructorFunction
import org.jetbrains.kotlin.resolve.sam.getSingleAbstractMethodOrNull

internal class Kt1SamResolver(override val analysisSession: Kt1AnalysisSession) : KtSamResolver() {
    override val token: ValidityToken
        get() = analysisSession.token

    override fun getSamConstructor(ktClassLikeSymbol: KtClassLikeSymbol): KtSamConstructorSymbol? = withValidityAssertion {
        val descriptor = getSymbolDescriptor(ktClassLikeSymbol)
        if (descriptor is ClassDescriptor && getSingleAbstractMethodOrNull(descriptor) != null) {
            val constructorDescriptor = createSamConstructorFunction(
                descriptor.containingDeclaration,
                descriptor,
                analysisSession.resolveSession.samConversionResolver,
                JvmSamConversionOracle(analysisSession.resolveSession.languageVersionSettings)
            )

            return Kt1DescSamConstructorSymbol(constructorDescriptor, analysisSession)
        }

        return null
    }
}