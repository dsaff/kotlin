/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescNamedClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtNamedClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.name.ClassId

class Kt1DescNamedClassOrObjectSymbolSymbol(private val classId: ClassId) : KtSymbolPointer<KtNamedClassOrObjectSymbol>() {
    @Deprecated("Consider using org.jetbrains.kotlin.analysis.api.KtAnalysisSession.restoreSymbol")
    override fun restoreSymbol(analysisSession: KtAnalysisSession): KtNamedClassOrObjectSymbol? {
        check(analysisSession is Kt1AnalysisSession)
        val descriptor = analysisSession.resolveSession.moduleDescriptor.findClassAcrossModuleDependencies(classId) ?: return null
        return Kt1DescNamedClassOrObjectSymbol(descriptor, analysisSession)
    }
}