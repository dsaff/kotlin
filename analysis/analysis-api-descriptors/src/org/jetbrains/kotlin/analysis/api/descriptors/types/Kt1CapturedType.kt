/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.types

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.ktNullability
import org.jetbrains.kotlin.analysis.api.descriptors.types.base.Kt1Type
import org.jetbrains.kotlin.analysis.api.descriptors.types.base.asStringForDebugging
import org.jetbrains.kotlin.analysis.api.types.KtCapturedType
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.resolve.calls.inference.CapturedType

internal class Kt1CapturedType(
    override val type: CapturedType,
    override val analysisSession: Kt1AnalysisSession
) : KtCapturedType(), Kt1Type {
    override fun asStringForDebugging(): String = withValidityAssertion { type.asStringForDebugging() }

    override val nullability: KtTypeNullability
        get() = withValidityAssertion { type.ktNullability }
}