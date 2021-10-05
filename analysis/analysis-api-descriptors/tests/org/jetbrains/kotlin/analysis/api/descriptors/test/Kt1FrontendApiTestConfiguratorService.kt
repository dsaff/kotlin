/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.test

import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.analysis.api.InvalidWayOfUsingAnalysisSession
import org.jetbrains.kotlin.analysis.api.KtAnalysisSessionProvider
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisHandlerExtension
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1CliAnalysisSessionProvider
import org.jetbrains.kotlin.analysis.api.descriptors.references.base.Kt1KotlinReferenceProviderContributor
import org.jetbrains.kotlin.analysis.api.impl.barebone.test.FrontendApiTestConfiguratorService
import org.jetbrains.kotlin.analysis.api.impl.base.references.HLApiReferenceProviderService
import org.jetbrains.kotlin.idea.references.KotlinReferenceProviderContributor
import org.jetbrains.kotlin.psi.KotlinReferenceProvidersService
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.lazy.JvmResolveUtil
import org.jetbrains.kotlin.test.bind
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider

object Kt1FrontendApiTestConfiguratorService : FrontendApiTestConfiguratorService {
    override fun TestConfigurationBuilder.configureTest(disposable: Disposable) {
        usePreAnalysisHandlers(::Kt1ModuleRegistrarPreAnalysisHandler.bind(disposable))
    }

    @OptIn(InvalidWayOfUsingAnalysisSession::class)
    override fun registerProjectServices(project: MockProject) {
        project.registerService(KtAnalysisSessionProvider::class.java, Kt1CliAnalysisSessionProvider(project))
        AnalysisHandlerExtension.registerExtension(project, Kt1AnalysisHandlerExtension())
    }

    override fun registerApplicationServices(application: MockApplication) {
        if (application.getServiceIfCreated(KotlinReferenceProvidersService::class.java) == null) {
            application.registerService(KotlinReferenceProvidersService::class.java, HLApiReferenceProviderService::class.java)
            application.registerService(KotlinReferenceProviderContributor::class.java, Kt1KotlinReferenceProviderContributor::class.java)
        }
    }
}

fun analyzeTestFiles(ktFiles: List<KtFile>, module: TestModule, testServices: TestServices) {
    val compilerConfigurationProvider = testServices.compilerConfigurationProvider
    val project = compilerConfigurationProvider.getProject(module)
    val compilerConfiguration = compilerConfigurationProvider.getCompilerConfiguration(module)
    val packageProviderFactory = compilerConfigurationProvider.getPackagePartProviderFactory(module)
    JvmResolveUtil.analyze(project, ktFiles, compilerConfiguration, packageProviderFactory)
}