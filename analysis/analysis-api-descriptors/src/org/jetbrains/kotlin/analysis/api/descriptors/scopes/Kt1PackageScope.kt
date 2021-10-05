/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.scopes

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.Kt1PackageSymbol
import org.jetbrains.kotlin.analysis.api.scopes.KtPackageScope
import org.jetbrains.kotlin.analysis.api.scopes.KtScopeNameFilter
import org.jetbrains.kotlin.analysis.api.symbols.KtConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtPackageSymbol
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.scopes.MemberScope

internal class Kt1PackageScope(
    scope: MemberScope,
    private val owner: KtPackageSymbol,
    analysisSession: Kt1AnalysisSession
) : Kt1ScopeMember(scope, analysisSession), KtPackageScope {
    override val fqName: FqName
        get() = withValidityAssertion { owner.fqName }

    override fun getPackageSymbols(nameFilter: KtScopeNameFilter): Sequence<KtPackageSymbol> = withValidityAssertion {
        val packageFragmentProvider = analysisSession.resolveSession.packageFragmentProvider
        return packageFragmentProvider.getSubPackagesOf(owner.fqName, nameFilter)
            .asSequence()
            .map { Kt1PackageSymbol(it, analysisSession) }
    }

    override fun getConstructors(): Sequence<KtConstructorSymbol> = withValidityAssertion {
        return emptySequence()
    }
}