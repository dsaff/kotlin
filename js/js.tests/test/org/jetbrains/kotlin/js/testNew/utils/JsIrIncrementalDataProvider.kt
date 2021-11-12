/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.testNew.utils

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.ic.ModuleCache
import org.jetbrains.kotlin.ir.backend.js.ic.ModuleName
import org.jetbrains.kotlin.ir.backend.js.ic.rebuildCacheForDirtyFiles
import org.jetbrains.kotlin.ir.backend.js.moduleName
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.js.test.TestModuleCache
import org.jetbrains.kotlin.konan.properties.propertyList
import org.jetbrains.kotlin.library.KLIB_PROPERTY_DEPENDS
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.test.directives.JsEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestService
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.JsEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.jsLibraryProvider
import java.io.File

class JsIrIncrementalDataProvider(private val testServices: TestServices) : TestService {
    private val fullRuntimeKlib: String = System.getProperty("kotlin.js.full.stdlib.path")
    private val defaultRuntimeKlib = System.getProperty("kotlin.js.reduced.stdlib.path")
    private val kotlinTestKLib = System.getProperty("kotlin.js.kotlin.test.path")

    private val predefinedKlibHasIcCache = mutableMapOf<String, TestModuleCache?>(
        File(fullRuntimeKlib).absolutePath to null,
        File(kotlinTestKLib).absolutePath to null,
        File(defaultRuntimeKlib).absolutePath to null
    )

    private val icCache: MutableMap<String, TestModuleCache> = mutableMapOf()

    fun getCaches(): Map<String, ModuleCache> {
        return icCache.map { it.key to it.value.createModuleCache() }.toMap()
    }

    fun getCacheForModule(module: TestModule): Map<String, ByteArray> {
        val path = JsEnvironmentConfigurator.getJsKlibArtifactPath(testServices, module.name)
        val canonicalPath = File(path).canonicalPath
        val moduleCache = icCache[canonicalPath] ?: error("No cache found for $path")

        val oldBinaryAsts = mutableMapOf<String, ByteArray>()
        val dataProvider = moduleCache.cacheProvider()
        val dataConsumer = moduleCache.cacheConsumer()

        for (testFile in module.files) {
            if (JsEnvironmentConfigurationDirectives.RECOMPILE in testFile.directives) {
                val fileName = "/${testFile.name}"
                oldBinaryAsts[fileName] = dataProvider.binaryAst(fileName) ?: error("No AST found for $fileName")
                dataConsumer.invalidateForFile(fileName)
            }
        }

        return oldBinaryAsts
    }

    private fun recordIncrementalDataForRuntimeKlib(module: TestModule) {
        val runtimeKlibPath = JsEnvironmentConfigurator.getStdlibPathsForModule(module)
        val libs = runtimeKlibPath.map {
            val descriptor = testServices.jsLibraryProvider.getDescriptorByPath(it)
            testServices.jsLibraryProvider.getCompiledLibraryByDescriptor(descriptor)
        }
        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(module)

        runtimeKlibPath.forEach {
            recordIncrementalData(it, null, libs, configuration)
        }
    }

    fun recordIncrementalData(module: TestModule, library: KotlinLibrary) {
        recordIncrementalDataForRuntimeKlib(module)

        val dirtyFiles = module.files.map { "/${it.relativePath}" }
        val path = JsEnvironmentConfigurator.getJsKlibArtifactPath(testServices, module.name)
        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(module)

        val allDependencies = JsEnvironmentConfigurator.getAllRecursiveLibrariesFor(module, testServices).keys.toList()
        recordIncrementalData(path, dirtyFiles, allDependencies + library, configuration)
    }

    private fun recordIncrementalData(path: String, dirtyFiles: List<String>?, allDependencies: List<KotlinLibrary>, configuration: CompilerConfiguration) {
        val canonicalPath = File(path).canonicalPath
        var moduleCache = predefinedKlibHasIcCache[canonicalPath]

        if (moduleCache == null) {
            moduleCache = icCache[canonicalPath] ?: TestModuleCache(canonicalPath)

            val libs = allDependencies.associateBy { File(it.libraryFile.path).canonicalPath }

            val nameToKotlinLibrary: Map<ModuleName, KotlinLibrary> = libs.values.associateBy { it.moduleName }

            val dependencyGraph = libs.values.associateWith {
                it.manifestProperties.propertyList(KLIB_PROPERTY_DEPENDS, escapeInQuotes = true).map { depName ->
                    nameToKotlinLibrary[depName] ?: error("No Library found for $depName")
                }
            }

            val currentLib = libs[File(canonicalPath).canonicalPath] ?: error("Expected library at $canonicalPath")

            rebuildCacheForDirtyFiles(currentLib, configuration, dependencyGraph, dirtyFiles, moduleCache.cacheConsumer(), IrFactoryImpl)

            if (canonicalPath in predefinedKlibHasIcCache) {
                predefinedKlibHasIcCache[canonicalPath] = moduleCache
            }
        }

        icCache[canonicalPath] = moduleCache
    }
}

val TestServices.jsIrIncrementalDataProvider: JsIrIncrementalDataProvider by TestServices.testServiceAccessor()
