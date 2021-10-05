/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.load.java.sam.JvmSamConversionOracle
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.sam.createSamConstructorFunction
import org.jetbrains.kotlin.resolve.sam.getSingleAbstractMethodOrNull

class Kt1DescSamConstructorSymbolPointer(private val classId: ClassId) : KtSymbolPointer<KtSamConstructorSymbol>() {
    @Deprecated("Consider using org.jetbrains.kotlin.analysis.api.KtAnalysisSession.restoreSymbol")
    override fun restoreSymbol(analysisSession: KtAnalysisSession): KtSamConstructorSymbol? {
        check(analysisSession is Kt1AnalysisSession)
        val samInterface = analysisSession.resolveSession.moduleDescriptor.findClassAcrossModuleDependencies(classId)
        if (samInterface == null || getSingleAbstractMethodOrNull(samInterface) == null) {
            return null
        }

        val constructorDescriptor = createSamConstructorFunction(
            samInterface.containingDeclaration,
            samInterface,
            analysisSession.resolveSession.samConversionResolver,
            JvmSamConversionOracle(analysisSession.resolveSession.languageVersionSettings)
        )

        return Kt1DescSamConstructorSymbol(constructorDescriptor, analysisSession)
    }
}