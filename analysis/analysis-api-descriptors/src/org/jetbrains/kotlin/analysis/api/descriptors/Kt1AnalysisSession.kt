/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.components.*
import org.jetbrains.kotlin.analysis.api.descriptors.components.*
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbolProvider
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.deprecation.DeprecationResolver
import org.jetbrains.kotlin.resolve.lazy.ResolveSession

@Suppress("LeakingThis")
abstract class Kt1AnalysisSession(val contextElement: KtElement, token: ValidityToken) : KtAnalysisSession(token) {
    override val smartCastProviderImpl: KtSmartCastProvider = Kt1SmartCastProvider(this)
    override val diagnosticProviderImpl: KtDiagnosticProvider = Kt1DiagnosticProvider(this)
    override val scopeProviderImpl: KtScopeProvider = Kt1ScopeProvider(this)
    override val containingDeclarationProviderImpl: KtSymbolContainingDeclarationProvider = Kt1SymbolContainingDeclarationProvider(this)
    override val symbolProviderImpl: KtSymbolProvider = Kt1SymbolProvider(this)
    override val callResolverImpl: KtCallResolver = Kt1CallResolver(this)
    override val completionCandidateCheckerImpl: KtCompletionCandidateChecker = Kt1CompletionCandidateChecker(this)
    override val symbolDeclarationOverridesProviderImpl: KtSymbolDeclarationOverridesProvider = Kt1SymbolDeclarationOverridesProvider(this)
    override val referenceShortenerImpl: KtReferenceShortener = Kt1ReferenceShortener(this)
    override val symbolDeclarationRendererProviderImpl: KtSymbolDeclarationRendererProvider = Kt1SymbolDeclarationRendererProvider(this)
    override val expressionTypeProviderImpl: KtExpressionTypeProvider = Kt1ExpressionTypeProvider(this)
    override val psiTypeProviderImpl: KtPsiTypeProvider = Kt1PsiTypeProvider(this)
    override val typeProviderImpl: KtTypeProvider = Kt1TypeProvider(this)
    override val typeInfoProviderImpl: KtTypeInfoProvider = Kt1TypeInfoProvider(this)
    override val subtypingComponentImpl: KtSubtypingComponent = Kt1SubtypingComponent(this)
    override val expressionInfoProviderImpl: KtExpressionInfoProvider = Kt1ExpressionInfoProvider(this)
    override val compileTimeConstantProviderImpl: KtCompileTimeConstantProvider = Kt1CompileTimeConstantProvider(this)
    override val visibilityCheckerImpl: KtVisibilityChecker = Kt1VisibilityChecker(this)
    override val overrideInfoProviderImpl: KtOverrideInfoProvider = Kt1OverrideInfoProvider(this)
    override val inheritorsProviderImpl: KtInheritorsProvider = Kt1InheritorsProvider(this)
    override val typesCreatorImpl: KtTypeCreator = Kt1TypeCreator(this)
    override val samResolverImpl: KtSamResolver = Kt1SamResolver(this)
    override val importOptimizerImpl: KtImportOptimizer = Kt1ImportOptimizer(this)
    override val jvmTypeMapperImpl: KtJvmTypeMapper = Kt1JvmTypeMapper(this)
    override val symbolInfoProviderImpl: KtSymbolInfoProvider = Kt1SymbolInfoProvider(this)

    abstract val resolveSession: ResolveSession
    abstract val deprecationResolver: DeprecationResolver

    abstract fun analyze(element: KtElement, mode: AnalysisMode = AnalysisMode.FULL): BindingContext

    abstract fun getOrigin(file: VirtualFile): KtSymbolOrigin

    enum class AnalysisMode {
        FULL,
        PARTIAL_WITH_DIAGNOSTICS,
        PARTIAL
    }
}