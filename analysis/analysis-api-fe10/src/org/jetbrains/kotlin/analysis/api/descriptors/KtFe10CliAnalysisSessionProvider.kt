/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.base.KtFe10Symbol
import org.jetbrains.kotlin.analysis.api.impl.base.CachingKtAnalysisSessionProvider
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbolOrigin
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.deprecation.DeprecationResolver
import org.jetbrains.kotlin.resolve.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

class KtFe10CliAnalysisSessionProvider(project: Project) : CachingKtAnalysisSessionProvider<KtFe10CliAnalysisSessionProvider.State>(project) {
    private val Project.analysisCompletedHandler: KtFe10AnalysisHandlerExtension
        get() {
            return AnalysisHandlerExtension.extensionPointName.getExtensions(this).firstIsInstanceOrNull()
                ?: error(KtFe10AnalysisHandlerExtension::class.java.name + " should be registered")
        }

    class State(val resolveSession: ResolveSession, val deprecationResolver: DeprecationResolver)

    override fun createAnalysisSession(
        resolveState: State,
        validityToken: ValidityToken,
        contextElement: KtElement
    ): KtAnalysisSession {
        return KtFe10CliAnalysisSession(resolveState.resolveSession, resolveState.deprecationResolver, contextElement, validityToken)
    }

    private fun getResolveState(project: Project): State {
        val handler = project.analysisCompletedHandler
        fun analysisNotStarted(): Nothing = error("Analysis has not started")

        return State(
            resolveSession = handler.resolveSession ?: analysisNotStarted(),
            deprecationResolver = handler.deprecationResolver ?: analysisNotStarted()
        )
    }

    override fun getResolveState(contextElement: KtElement): State {
        return getResolveState(contextElement.project)
    }

    override fun getResolveState(contextSymbol: KtSymbol): State {
        if (contextSymbol is KtFe10Symbol) {
            return State(contextSymbol.analysisSession.resolveSession, contextSymbol.analysisSession.deprecationResolver)
        } else {
            val project = contextSymbol.psi?.project
            if (project != null) {
                return getResolveState(project)
            }
        }

        throw IllegalArgumentException("Unsupported symbol kind: $contextSymbol")
    }
}

private class KtFe10CliAnalysisSession(
    override val resolveSession: ResolveSession,
    override val deprecationResolver: DeprecationResolver,
    contextElement: KtElement,
    token: ValidityToken
) : KtFe10AnalysisSession(contextElement, token) {
    override fun analyze(element: KtElement, mode: AnalysisMode): BindingContext {
        return resolveSession.bindingContext
    }

    override fun getOrigin(file: VirtualFile): KtSymbolOrigin {
        return KtSymbolOrigin.LIBRARY
    }

    override fun createContextDependentCopy(originalKtFile: KtFile, elementToReanalyze: KtElement): KtAnalysisSession {
        return KtFe10CliAnalysisSession(resolveSession, deprecationResolver, elementToReanalyze, token)
    }
}

class KtFe10AnalysisHandlerExtension : AnalysisHandlerExtension {
    var resolveSession: ResolveSession? = null
        private set

    var deprecationResolver: DeprecationResolver? = null
        private set

    override fun doAnalysis(
        project: Project,
        module: ModuleDescriptor,
        projectContext: ProjectContext,
        files: Collection<KtFile>,
        bindingTrace: BindingTrace,
        componentProvider: ComponentProvider
    ): AnalysisResult? {
        resolveSession = componentProvider.get()
        deprecationResolver = componentProvider.get()
        return super.doAnalysis(project, module, projectContext, files, bindingTrace, componentProvider)
    }
}