/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.base.Kt1AnnotatedSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.pointers.Kt1NeverRestoringSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.KtFileSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithDeclarations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.psi.KtFile

internal class Kt1FileSymbol(
    private val file: KtFile,
    override val analysisSession: Kt1AnalysisSession
) : KtFileSymbol(), KtSymbolWithDeclarations, Kt1AnnotatedSymbol {
    override val psi: KtFile
        get() = withValidityAssertion { file }

    override val annotationsObject: Annotations
        get() = analysisSession.resolveSession.getFileAnnotations(file)

    override val origin: KtSymbolOrigin
        get() = withValidityAssertion { if (file.isCompiled) KtSymbolOrigin.LIBRARY else KtSymbolOrigin.SOURCE }

    override fun createPointer(): KtSymbolPointer<KtFileSymbol> = withValidityAssertion {
        return KtPsiBasedSymbolPointer.createForSymbolFromSource(this) ?: Kt1NeverRestoringSymbolPointer()
    }
}