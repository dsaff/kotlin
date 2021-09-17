/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.diagnostics.impl

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.analysis.diagnostics.AbstractKtDiagnosticFactory
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.KtDiagnostic

class DeduplicatingDiagnosticReporter(private val inner: DiagnosticReporter) : DiagnosticReporter() {

    private val reported = mutableSetOf<Pair<KtSourceElement, AbstractKtDiagnosticFactory>>()

    override fun report(diagnostic: KtDiagnostic?, context: DiagnosticContext) {
        if (diagnostic != null && reported.add(Pair(diagnostic.element, diagnostic.factory))) {
            inner.report(diagnostic, context)
        }
    }
}

fun DiagnosticReporter.deduplicating(): DeduplicatingDiagnosticReporter = DeduplicatingDiagnosticReporter(this)