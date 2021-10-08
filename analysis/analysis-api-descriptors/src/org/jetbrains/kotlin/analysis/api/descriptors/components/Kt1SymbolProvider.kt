/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.components

import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.Kt1FileSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.Kt1PackageSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtClassSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter

internal class Kt1SymbolProvider(override val analysisSession: Kt1AnalysisSession) : KtSymbolProvider() {
    override val token: ValidityToken
        get() = analysisSession.token

    override val ROOT_PACKAGE_SYMBOL: KtPackageSymbol
        get() = Kt1PackageSymbol(FqName.ROOT, analysisSession)

    override fun getFileSymbol(psi: KtFile): KtFileSymbol = withValidityAssertion {
        return Kt1FileSymbol(psi, analysisSession)
    }

    override fun getParameterSymbol(psi: KtParameter): KtValueParameterSymbol = withValidityAssertion {
        return Kt1PsiValueParameterSymbol(psi, analysisSession)
    }

    override fun getFunctionLikeSymbol(psi: KtNamedFunction): KtFunctionLikeSymbol = withValidityAssertion {
        return if (psi.hasBody() && (psi.funKeyword == null || psi.nameIdentifier == null)) {
            getAnonymousFunctionSymbol(psi)
        } else {
            Kt1PsiFunctionSymbol(psi, analysisSession)
        }
    }

    override fun getConstructorSymbol(psi: KtConstructor<*>): KtConstructorSymbol = withValidityAssertion {
        return Kt1PsiConstructorSymbol(psi, analysisSession)
    }

    override fun getTypeParameterSymbol(psi: KtTypeParameter): KtTypeParameterSymbol = withValidityAssertion {
        return Kt1PsiTypeParameterSymbol(psi, analysisSession)
    }

    override fun getTypeAliasSymbol(psi: KtTypeAlias): KtTypeAliasSymbol = withValidityAssertion {
        return Kt1PsiTypeAliasSymbol(psi, analysisSession)
    }

    override fun getEnumEntrySymbol(psi: KtEnumEntry): KtEnumEntrySymbol = withValidityAssertion {
        return Kt1PsiEnumEntrySymbol(psi, analysisSession)
    }

    override fun getAnonymousFunctionSymbol(psi: KtNamedFunction): KtAnonymousFunctionSymbol = withValidityAssertion {
        return Kt1PsiAnonymousFunctionSymbol(psi, analysisSession)
    }

    override fun getAnonymousFunctionSymbol(psi: KtFunctionLiteral): KtAnonymousFunctionSymbol = withValidityAssertion {
        return Kt1PsiLiteralAnonymousFunctionSymbol(psi, analysisSession)
    }

    override fun getVariableSymbol(psi: KtProperty): KtVariableSymbol = withValidityAssertion {
        return if (psi.isLocal) {
            Kt1PsiLocalVariableSymbol(psi, analysisSession)
        } else {
            Kt1PsiKotlinPropertySymbol(psi, analysisSession)
        }
    }

    override fun getAnonymousObjectSymbol(psi: KtObjectLiteralExpression): KtAnonymousObjectSymbol = withValidityAssertion {
        return Kt1PsiAnonymousObjectSymbol(psi.objectDeclaration, analysisSession)
    }

    override fun getClassOrObjectSymbol(psi: KtClassOrObject): KtClassOrObjectSymbol = withValidityAssertion {
        return if (psi is KtObjectDeclaration && psi.isObjectLiteral()) {
            Kt1PsiAnonymousObjectSymbol(psi, analysisSession)
        } else {
            Kt1PsiNamedClassOrObjectSymbol(psi, analysisSession)
        }
    }

    override fun getNamedClassOrObjectSymbol(psi: KtClassOrObject): KtNamedClassOrObjectSymbol? = withValidityAssertion {
        if (psi is KtEnumEntry || psi.nameIdentifier == null) {
            return null
        }

        return Kt1PsiNamedClassOrObjectSymbol(psi, analysisSession)
    }

    override fun getPropertyAccessorSymbol(psi: KtPropertyAccessor): KtPropertyAccessorSymbol = withValidityAssertion {
        return if (psi.isGetter) {
            Kt1PsiPropertyGetterSymbol(psi, analysisSession)
        } else {
            Kt1PsiPropertySetterSymbol(psi, analysisSession)
        }
    }

    override fun getClassInitializerSymbol(psi: KtClassInitializer): KtClassInitializerSymbol = withValidityAssertion {
        return Kt1PsiClassInitializerSymbol(psi, analysisSession)
    }

    override fun getClassOrObjectSymbolByClassId(classId: ClassId): KtClassOrObjectSymbol? = withValidityAssertion {
        val descriptor = analysisSession.resolveSession.moduleDescriptor.findClassAcrossModuleDependencies(classId) ?: return null
        return descriptor.toKtClassSymbol(analysisSession)
    }

    override fun getTopLevelCallableSymbols(packageFqName: FqName, name: Name): Sequence<KtSymbol> = withValidityAssertion {
        val packageViewDescriptor = analysisSession.resolveSession.moduleDescriptor.getPackage(packageFqName)
        return packageViewDescriptor.memberScope.getContributedDescriptors(DescriptorKindFilter.ALL, nameFilter = { it == name })
            .asSequence()
            .filter { it.name == name }
            .mapNotNull { it.toKtSymbol(analysisSession) as? KtCallableSymbol }
    }
}