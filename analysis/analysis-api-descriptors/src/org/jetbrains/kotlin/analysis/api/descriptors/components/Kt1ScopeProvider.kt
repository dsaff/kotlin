/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.components

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.analysis.api.components.KtImplicitReceiver
import org.jetbrains.kotlin.analysis.api.components.KtScopeContext
import org.jetbrains.kotlin.analysis.api.components.KtScopeProvider
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.scopes.*
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.Kt1FileSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.Kt1PackageSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.base.Kt1Symbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.Kt1DescSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtType
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.base.Kt1PsiSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.base.getResolutionScope
import org.jetbrains.kotlin.analysis.api.descriptors.types.base.Kt1Type
import org.jetbrains.kotlin.analysis.api.scopes.*
import org.jetbrains.kotlin.analysis.api.symbols.KtFileSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithDeclarations
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithMembers
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.packageFragments
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.scopes.ChainedMemberScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import org.jetbrains.kotlin.util.containingNonLocalDeclaration

internal class Kt1ScopeProvider(override val analysisSession: Kt1AnalysisSession) : KtScopeProvider() {
    private companion object {
        val LOG = Logger.getInstance(Kt1ScopeProvider::class.java)
    }

    override val token: ValidityToken
        get() = analysisSession.token

    override fun getMemberScope(classSymbol: KtSymbolWithMembers): KtMemberScope = withValidityAssertion {
        val descriptor = getDescriptor<ClassDescriptor>(classSymbol)
            ?: return object : Kt1EmptyScope(token), KtMemberScope {
                override val owner get() = classSymbol
            }

        // TODO either this or declared scope should return a different set of members
        return object : Kt1ScopeMember(descriptor.unsubstitutedMemberScope, analysisSession), KtMemberScope {
            override val owner get() = classSymbol
        }
    }

    override fun getDeclaredMemberScope(classSymbol: KtSymbolWithMembers): KtDeclaredMemberScope = withValidityAssertion {
        val descriptor = getDescriptor<ClassDescriptor>(classSymbol)
            ?: return object : Kt1EmptyScope(token), KtDeclaredMemberScope {
                override val owner get() = classSymbol
            }

        return object : Kt1ScopeMember(descriptor.unsubstitutedMemberScope, analysisSession), KtDeclaredMemberScope {
            override val owner get() = classSymbol
        }
    }

    override fun getStaticMemberScope(symbol: KtSymbolWithMembers): KtScope = withValidityAssertion {
        val descriptor = getDescriptor<ClassDescriptor>(symbol) ?: return Kt1EmptyScope(token)
        return Kt1ScopeMember(descriptor.staticScope, analysisSession)
    }

    override fun getFileScope(fileSymbol: KtFileSymbol): KtDeclarationScope<KtSymbolWithDeclarations> = withValidityAssertion {
        require(fileSymbol is Kt1FileSymbol)
        val scope = analysisSession.resolveSession.fileScopeProvider.getFileResolutionScope(fileSymbol.psi)

        return object : Kt1ScopeLexical(scope, analysisSession), KtDeclarationScope<KtSymbolWithDeclarations> {
            override val owner: KtSymbolWithDeclarations
                get() = withValidityAssertion { fileSymbol }
        }
    }

    override fun getPackageScope(packageSymbol: KtPackageSymbol): KtPackageScope = withValidityAssertion {
        require(packageSymbol is Kt1PackageSymbol)
        val packageFragments = analysisSession.resolveSession.packageFragmentProvider.packageFragments(packageSymbol.fqName)
        val scopeDescription = "Compound scope for package \"${packageSymbol.fqName}\""
        val chainedScope = ChainedMemberScope.create(scopeDescription, packageFragments.map { it.getMemberScope() })
        return Kt1PackageScope(chainedScope, packageSymbol, analysisSession)
    }

    override fun getCompositeScope(subScopes: List<KtScope>): KtCompositeScope = withValidityAssertion {
        return Kt1CompositeScope(subScopes, token)
    }

    override fun getTypeScope(type: KtType): KtScope = withValidityAssertion {
        require(type is Kt1Type)
        return Kt1ScopeMember(type.type.memberScope, analysisSession)
    }

    override fun getScopeContextForPosition(originalFile: KtFile, positionInFakeFile: KtElement): KtScopeContext = withValidityAssertion {
        val elementToAnalyze = positionInFakeFile.containingNonLocalDeclaration() ?: originalFile
        val bindingContext = analysisSession.analyze(elementToAnalyze)

        val lexicalScope = positionInFakeFile.getResolutionScope(bindingContext)
        if (lexicalScope != null) {
            val compositeScope = Kt1CompositeScope(listOf(Kt1ScopeLexical(lexicalScope, analysisSession)), token)
            return KtScopeContext(compositeScope, collectImplicitReceivers(lexicalScope))
        }

        val fileScope = analysisSession.resolveSession.fileScopeProvider.getFileResolutionScope(originalFile)
        val compositeScope = Kt1CompositeScope(listOf(Kt1ScopeLexical(fileScope, analysisSession)), token)
        return KtScopeContext(compositeScope, collectImplicitReceivers(fileScope))
    }

    private inline fun <reified T : DeclarationDescriptor> getDescriptor(symbol: KtSymbol): T? {
        return when (symbol) {
            is Kt1DescSymbol<*> -> symbol.descriptor as? T
            is Kt1PsiSymbol<*, *> -> symbol.descriptor as? T
            else -> {
                assert(symbol is Kt1Symbol) { "Unrecognized symbol implementation found" }
                null
            }
        }
    }

    private fun collectImplicitReceivers(scope: LexicalScope): MutableList<KtImplicitReceiver> {
        val result = mutableListOf<KtImplicitReceiver>()

        for (implicitReceiver in scope.getImplicitReceiversHierarchy()) {
            val type = implicitReceiver.type.toKtType(analysisSession)
            val ownerDescriptor = implicitReceiver.containingDeclaration
            val owner = ownerDescriptor.toKtSymbol(analysisSession)

            if (owner == null) {
                LOG.error("Unexpected implicit receiver owner: $ownerDescriptor (${ownerDescriptor.javaClass})")
                continue
            }

            result += KtImplicitReceiver(token, type, owner)
        }

        return result
    }
}