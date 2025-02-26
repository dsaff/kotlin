/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.llvm.objcexport

import kotlinx.cinterop.toKString
import llvm.*
import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.ir.allParametersCount
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.descriptors.ClassLayoutBuilder
import org.jetbrains.kotlin.backend.konan.descriptors.OverriddenFunctionInfo
import org.jetbrains.kotlin.backend.konan.descriptors.allOverriddenFunctions
import org.jetbrains.kotlin.backend.konan.descriptors.isAbstract
import org.jetbrains.kotlin.backend.konan.ir.*
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.objc.ObjCCodeGenerator
import org.jetbrains.kotlin.backend.konan.llvm.objc.ObjCDataGenerator
import org.jetbrains.kotlin.backend.konan.objcexport.*
import org.jetbrains.kotlin.backend.konan.serialization.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.konan.CompiledKlibModuleOrigin
import org.jetbrains.kotlin.descriptors.konan.CurrentKlibModuleOrigin
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltInsOverDescriptors
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.konan.ForeignExceptionMode
import org.jetbrains.kotlin.konan.target.AppleConfigurables
import org.jetbrains.kotlin.konan.target.LinkerOutputKind
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.DFS

internal fun TypeBridge.makeNothing() = when (this) {
    is ReferenceBridge, is BlockPointerBridge -> kNullInt8Ptr
    is ValueTypeBridge -> LLVMConstNull(this.objCValueType.llvmType)!!
}

internal class ObjCExportFunctionGenerationContext(
        builder: ObjCExportFunctionGenerationContextBuilder
) : FunctionGenerationContext(builder) {
    private val objCExportCodegen = builder.objCExportCodegen

    // All generated bridges by ObjCExport should have `LeaveFrame`
    // because there is no guarantee of catching Kotlin exception in Kotlin code.
    override val needCleanupLandingpadAndLeaveFrame: Boolean
        get() = true

    // Note: we could generate single "epilogue" and make all [ret]s just branch to it (like [DefaultFunctionGenerationContext]),
    // but this would be useless for most of the usages, which have only one [ret].
    // Remaining usages can be optimized ad hoc.

    override fun ret(value: LLVMValueRef?): LLVMValueRef = if (value == null) {
        retVoid()
    } else {
        retValue(value)
    }

    /**
     * autoreleases and returns [value].
     * It is equivalent to `ret(autorelease(value))`, but optimizes the autorelease out if the caller is prepared for it.
     *
     * See the Clang documentation and the Obj-C runtime source code for more details:
     * https://clang.llvm.org/docs/AutomaticReferenceCounting.html#arc-runtime-objc-autoreleasereturnvalue
     * https://github.com/opensource-apple/objc4/blob/cd5e62a5597ea7a31dccef089317abb3a661c154/runtime/objc-object.h#L930
     */
    fun autoreleaseAndRet(value: LLVMValueRef) {
        onReturn()
        // Note: it is important to make this call tail (otherwise the elimination magic won't work),
        // so it should go after other "epilogue" instructions, and that's why we couldn't just use
        // ret(autorelease(value))
        val result = call(objCExportCodegen.objcAutoreleaseReturnValue, listOf(value))
        LLVMSetTailCall(result, 1)
        rawRet(result)
    }

    override fun processReturns() {
        // Do nothing.
    }
}

internal class ObjCExportFunctionGenerationContextBuilder(
        functionType: LlvmFunctionSignature,
        functionName: String,
        val objCExportCodegen: ObjCExportCodeGeneratorBase
) : FunctionGenerationContextBuilder<ObjCExportFunctionGenerationContext>(
        functionType.llvmFunctionType,
        functionName,
        objCExportCodegen.codegen
) {
    // Not a very pleasant way to provide attributes to the generated function.
    init {
        functionType.addFunctionAttributes(function)
    }

    override fun build() = ObjCExportFunctionGenerationContext(this)
}

internal inline fun ObjCExportCodeGeneratorBase.functionGenerator(
        functionType: LlvmFunctionSignature,
        functionName: String,
        configure: ObjCExportFunctionGenerationContextBuilder.() -> Unit = {}
): ObjCExportFunctionGenerationContextBuilder = ObjCExportFunctionGenerationContextBuilder(
        functionType,
        functionName,
        this
).apply(configure)

internal open class ObjCExportCodeGeneratorBase(codegen: CodeGenerator) : ObjCCodeGenerator(codegen) {
    val symbols get() = context.ir.symbols
    val runtime get() = codegen.runtime
    val staticData get() = codegen.staticData

    val rttiGenerator = RTTIGenerator(context)

    private val objcTerminate: LlvmCallable by lazy {
        context.llvm.externalFunction(LlvmFunctionProto(
                "objc_terminate",
                LlvmRetType(voidType),
                functionAttributes = listOf(LlvmFunctionAttribute.NoUnwind),
                origin = CurrentKlibModuleOrigin
        ))
    }

    fun dispose() {
        rttiGenerator.dispose()
    }

    fun FunctionGenerationContext.callFromBridge(
            llvmFunction: LLVMValueRef,
            args: List<LLVMValueRef>,
            resultLifetime: Lifetime = Lifetime.IRRELEVANT,
            toNative: Boolean = false,
    ): LLVMValueRef {
        val llvmDeclarations = LlvmCallable(
                llvmFunction,
                // llvmFunction could be a function pointer here, and we can't infer attributes from it.
                LlvmFunctionAttributeProvider.makeEmpty()
        )
        return callFromBridge(llvmDeclarations, args, resultLifetime, toNative)
    }

    // TODO: currently bridges don't have any custom `landingpad`s,
    // so it is correct to use [callAtFunctionScope] here.
    // However, exception handling probably should be refactored
    // (e.g. moved from `IrToBitcode.kt` to [FunctionGenerationContext]).
    fun FunctionGenerationContext.callFromBridge(
            function: LlvmCallable,
            args: List<LLVMValueRef>,
            resultLifetime: Lifetime = Lifetime.IRRELEVANT,
            toNative: Boolean = false,
    ): LLVMValueRef {

        // TODO: it is required only for Kotlin-to-Objective-C bridges.
        this.forwardingForeignExceptionsTerminatedWith = objcTerminate

        val switchStateToNative = toNative && context.config.memoryModel == MemoryModel.EXPERIMENTAL
        val exceptionHandler: ExceptionHandler

        if (switchStateToNative) {
            switchThreadState(ThreadState.Native)
            // Note: this is suboptimal. We should forbid Kotlin exceptions thrown from native code, and use simple fatal handler here.
            exceptionHandler = filteringExceptionHandler(ExceptionHandler.Caller, ForeignExceptionMode.default, switchThreadState = true)
        } else {
            exceptionHandler = ExceptionHandler.Caller
        }

        val result = call(function, args, resultLifetime, exceptionHandler)

        if (switchStateToNative) {
            switchThreadState(ThreadState.Runnable)
        }

        return result
    }

    fun FunctionGenerationContext.kotlinReferenceToLocalObjC(value: LLVMValueRef) =
            callFromBridge(context.llvm.Kotlin_ObjCExport_refToLocalObjC, listOf(value))

    fun FunctionGenerationContext.kotlinReferenceToRetainedObjC(value: LLVMValueRef) =
            callFromBridge(context.llvm.Kotlin_ObjCExport_refToRetainedObjC, listOf(value))

    fun FunctionGenerationContext.objCReferenceToKotlin(value: LLVMValueRef, resultLifetime: Lifetime) =
            callFromBridge(context.llvm.Kotlin_ObjCExport_refFromObjC, listOf(value), resultLifetime)

    private val blockToKotlinFunctionConverterCache = mutableMapOf<BlockPointerBridge, LLVMValueRef>()

    internal fun blockToKotlinFunctionConverter(bridge: BlockPointerBridge): LLVMValueRef =
            blockToKotlinFunctionConverterCache.getOrPut(bridge) {
                generateBlockToKotlinFunctionConverter(bridge)
            }

    protected val blockGenerator = BlockGenerator(this.codegen)
    private val functionToRetainedBlockConverterCache = mutableMapOf<BlockPointerBridge, LLVMValueRef>()

    internal fun kotlinFunctionToRetainedBlockConverter(bridge: BlockPointerBridge): LLVMValueRef =
            functionToRetainedBlockConverterCache.getOrPut(bridge) {
                blockGenerator.run {
                    generateConvertFunctionToRetainedBlock(bridge)
                }
            }
}

internal class ObjCExportBlockCodeGenerator(codegen: CodeGenerator) : ObjCExportCodeGeneratorBase(codegen) {
    init {
        // Must be generated along with stdlib:
        // 1. Enumerates [BuiltInFictitiousFunctionIrClassFactory] built classes, which may be incomplete otherwise.
        // 2. Modifies stdlib global initializers.
        // 3. Defines runtime-declared globals.
        require(context.producedLlvmModuleContainsStdlib)
    }

    fun generate() {
        emitFunctionConverters()
        emitBlockToKotlinFunctionConverters()
        dispose()
    }
}

internal class ObjCExportCodeGenerator(
        codegen: CodeGenerator,
        val namer: ObjCExportNamer,
        val mapper: ObjCExportMapper
) : ObjCExportCodeGeneratorBase(codegen) {

    val selectorsToDefine = mutableMapOf<String, MethodBridge>()

    val externalGlobalInitializers = mutableMapOf<LLVMValueRef, ConstValue>()

    internal val continuationToRetainedCompletionConverter: LLVMValueRef by lazy {
        generateContinuationToRetainedCompletionConverter(blockGenerator)
    }

    fun meaningfulBridgeNameOrNull(irFunction: IrFunction?): String? {
        if (!context.config.configuration.getBoolean(KonanConfigKeys.MEANINGFUL_BRIDGE_NAMES)) {
            return null
        }
        return irFunction?.name?.asString()
    }

    fun FunctionGenerationContext.genSendMessage(
            returnType: LlvmParamType,
            parameterTypes: List<LlvmParamType>,
            receiver: LLVMValueRef,
            selector: String,
            switchToNative: Boolean,
            vararg args: LLVMValueRef,
    ): LLVMValueRef {

        val objcMsgSendType = LlvmFunctionSignature(
                returnType,
                listOf(LlvmParamType(int8TypePtr), LlvmParamType(int8TypePtr)) + parameterTypes
        )
        return callFromBridge(msgSender(objcMsgSendType), listOf(receiver, genSelector(selector)) + args, toNative = switchToNative)
    }

    fun FunctionGenerationContext.kotlinToObjC(
            value: LLVMValueRef,
            valueType: ObjCValueType
    ): LLVMValueRef = when (valueType) {
        ObjCValueType.BOOL -> zext(value, int8Type) // TODO: zext behaviour may be strange on bit types.

        ObjCValueType.UNICHAR,
        ObjCValueType.CHAR, ObjCValueType.SHORT, ObjCValueType.INT, ObjCValueType.LONG_LONG,
        ObjCValueType.UNSIGNED_CHAR, ObjCValueType.UNSIGNED_SHORT, ObjCValueType.UNSIGNED_INT,
        ObjCValueType.UNSIGNED_LONG_LONG,
        ObjCValueType.FLOAT, ObjCValueType.DOUBLE, ObjCValueType.POINTER -> value
    }

    private fun FunctionGenerationContext.objCToKotlin(
            value: LLVMValueRef,
            valueType: ObjCValueType
    ): LLVMValueRef = when (valueType) {
        ObjCValueType.BOOL -> icmpNe(value, Int8(0).llvm)

        ObjCValueType.UNICHAR,
        ObjCValueType.CHAR, ObjCValueType.SHORT, ObjCValueType.INT, ObjCValueType.LONG_LONG,
        ObjCValueType.UNSIGNED_CHAR, ObjCValueType.UNSIGNED_SHORT, ObjCValueType.UNSIGNED_INT,
        ObjCValueType.UNSIGNED_LONG_LONG,
        ObjCValueType.FLOAT, ObjCValueType.DOUBLE, ObjCValueType.POINTER -> value
    }

    private fun FunctionGenerationContext.objCBlockPointerToKotlin(
            value: LLVMValueRef,
            typeBridge: BlockPointerBridge,
            resultLifetime: Lifetime
    ) = callFromBridge(
            blockToKotlinFunctionConverter(typeBridge),
            listOf(value),
            resultLifetime
    )

    private fun FunctionGenerationContext.kotlinFunctionToObjCBlockPointer(
            typeBridge: BlockPointerBridge,
            value: LLVMValueRef
    ) = callFromBridge(objcAutorelease, listOf(kotlinFunctionToRetainedObjCBlockPointer(typeBridge, value)))

    internal fun FunctionGenerationContext.kotlinFunctionToRetainedObjCBlockPointer(
            typeBridge: BlockPointerBridge,
            value: LLVMValueRef
    ) = callFromBridge(kotlinFunctionToRetainedBlockConverter(typeBridge), listOf(value))

    fun FunctionGenerationContext.kotlinToLocalObjC(
            value: LLVMValueRef,
            typeBridge: TypeBridge
    ): LLVMValueRef = if (LLVMTypeOf(value) == voidType) {
        typeBridge.makeNothing()
    } else {
        when (typeBridge) {
            is ReferenceBridge -> kotlinReferenceToLocalObjC(value)
            is BlockPointerBridge -> kotlinFunctionToObjCBlockPointer(typeBridge, value) // TODO: use stack-allocated block here.
            is ValueTypeBridge -> kotlinToObjC(value, typeBridge.objCValueType)
        }
    }

    fun FunctionGenerationContext.objCToKotlin(
            value: LLVMValueRef,
            typeBridge: TypeBridge,
            resultLifetime: Lifetime
    ): LLVMValueRef = when (typeBridge) {
        is ReferenceBridge -> objCReferenceToKotlin(value, resultLifetime)
        is BlockPointerBridge -> objCBlockPointerToKotlin(value, typeBridge, resultLifetime)
        is ValueTypeBridge -> objCToKotlin(value, typeBridge.objCValueType)
    }

    fun FunctionGenerationContext.initRuntimeIfNeeded() {
        this.needsRuntimeInit = true
    }

    inline fun FunctionGenerationContext.convertKotlin(
            genValue: (Lifetime) -> LLVMValueRef,
            actualType: IrType,
            expectedType: IrType,
            resultLifetime: Lifetime
    ): LLVMValueRef {

        val conversion = symbols.getTypeConversion(actualType, expectedType)
                ?: return genValue(resultLifetime)

        val value = genValue(Lifetime.ARGUMENT)

        return callFromBridge(conversion.owner.llvmFunction, listOf(value), resultLifetime)
    }

    private fun generateTypeAdaptersForKotlinTypes(spec: ObjCExportCodeSpec?): List<ObjCTypeAdapter> {
        val types = spec?.types.orEmpty() + objCClassForAny

        val allReverseAdapters = createReverseAdapters(types)

        return types.map {
            val reverseAdapters = allReverseAdapters.getValue(it).adapters
            when (it) {
                objCClassForAny -> {
                    createTypeAdapter(it, superClass = null, reverseAdapters)
                }

                is ObjCClassForKotlinClass -> {
                    val superClass = it.superClassNotAny ?: objCClassForAny

                    dataGenerator.emitEmptyClass(it.binaryName, superClass.binaryName)
                    // Note: it is generated only to be visible for linker.
                    // Methods will be added at runtime.

                    createTypeAdapter(it, superClass, reverseAdapters)
                }

                is ObjCProtocolForKotlinInterface -> createTypeAdapter(it, superClass = null, reverseAdapters)
            }
        }
    }

    private fun generateTypeAdapters(spec: ObjCExportCodeSpec?) {
        val objCTypeAdapters = mutableListOf<ObjCTypeAdapter>()

        objCTypeAdapters += generateTypeAdaptersForKotlinTypes(spec)

        spec?.files?.forEach {
            objCTypeAdapters += createTypeAdapterForFileClass(it)
            dataGenerator.emitEmptyClass(it.binaryName, namer.kotlinAnyName.binaryName)
        }

        emitTypeAdapters(objCTypeAdapters)
    }

    internal fun generate(spec: ObjCExportCodeSpec?) {
        generateTypeAdapters(spec)

        NSNumberKind.values().mapNotNull { it.mappedKotlinClassId }.forEach {
            dataGenerator.exportClass(namer.numberBoxName(it).binaryName)
        }
        dataGenerator.exportClass(namer.mutableSetName.binaryName)
        dataGenerator.exportClass(namer.mutableMapName.binaryName)
        dataGenerator.exportClass(namer.kotlinAnyName.binaryName)

        emitSpecialClassesConvertions()

        // Replace runtime global with weak linkage:
        replaceExternalWeakOrCommonGlobal(
                "Kotlin_ObjCInterop_uniquePrefix",
                codegen.staticData.cStringLiteral(namer.topLevelNamePrefix),
                context.standardLlvmSymbolsOrigin
        )

        emitSelectorsHolder()

        emitStaticInitializers()

        emitKt42254Hint()
    }

    private fun emitTypeAdapters(objCTypeAdapters: List<ObjCTypeAdapter>) {
        val placedClassAdapters = mutableMapOf<String, ConstPointer>()
        val placedInterfaceAdapters = mutableMapOf<String, ConstPointer>()

        objCTypeAdapters.forEach { adapter ->
            val typeAdapter = staticData.placeGlobal("", adapter).pointer
            val irClass = adapter.irClass

            val descriptorToAdapter = if (irClass?.isInterface == true) {
                placedInterfaceAdapters
            } else {
                // Objective-C class for Kotlin class or top-level declarations.
                placedClassAdapters
            }
            descriptorToAdapter[adapter.objCName] = typeAdapter

            if (irClass != null) {
                if (!context.llvmModuleSpecification.importsKotlinDeclarationsFromOtherSharedLibraries()) {
                    setObjCExportTypeInfo(irClass, typeAdapter = typeAdapter)
                } else {
                    // Optimization: avoid generating huge initializers;
                    // handled with "Kotlin_ObjCExport_initTypeAdapters" below.
                }
            }
        }

        fun emitSortedAdapters(nameToAdapter: Map<String, ConstPointer>, prefix: String) {
            val sortedAdapters = nameToAdapter.toList().sortedBy { it.first }.map {
                it.second
            }

            if (sortedAdapters.isNotEmpty()) {
                val type = sortedAdapters.first().llvmType
                val sortedAdaptersPointer = staticData.placeGlobalConstArray("", type, sortedAdapters)

                // Note: this globals replace runtime globals with weak linkage:
                val origin = context.standardLlvmSymbolsOrigin
                replaceExternalWeakOrCommonGlobal(prefix, sortedAdaptersPointer, origin)
                replaceExternalWeakOrCommonGlobal("${prefix}Num", Int32(sortedAdapters.size), origin)
            }
        }

        emitSortedAdapters(placedClassAdapters, "Kotlin_ObjCExport_sortedClassAdapters")
        emitSortedAdapters(placedInterfaceAdapters, "Kotlin_ObjCExport_sortedProtocolAdapters")

        if (context.llvmModuleSpecification.importsKotlinDeclarationsFromOtherSharedLibraries()) {
            replaceExternalWeakOrCommonGlobal(
                    "Kotlin_ObjCExport_initTypeAdapters",
                    Int1(true),
                    context.standardLlvmSymbolsOrigin
            )
        }
    }

    private fun emitStaticInitializers() {
        if (externalGlobalInitializers.isEmpty()) return

        val initializer = generateFunctionNoRuntime(codegen, functionType(voidType, false), "initObjCExportGlobals") {
            externalGlobalInitializers.forEach { (global, value) ->
                store(value.llvm, global)
            }
            ret(null)
        }

        LLVMSetLinkage(initializer, LLVMLinkage.LLVMInternalLinkage)

        context.llvm.otherStaticInitializers += initializer
    }

    private fun emitKt42254Hint() {
        if (determineLinkerOutput(context) == LinkerOutputKind.STATIC_LIBRARY) {
            // Might be affected by https://youtrack.jetbrains.com/issue/KT-42254.
            // The code below generally follows [replaceExternalWeakOrCommonGlobal] implementation.
            if (context.llvmModuleSpecification.importsKotlinDeclarationsFromOtherObjectFiles()) {
                // So the compiler uses caches. If a user is linking two such static frameworks into a single binary,
                // the linker might fail with a lot of "duplicate symbol" errors due to KT-42254.
                // Adding a similar symbol that would explicitly hint to take a look at the YouTrack issue if reported.
                // Note: for some reason this symbol is reported as the last one, which is good for its purpose.
                val name = "See https://youtrack.jetbrains.com/issue/KT-42254"
                val global = staticData.placeGlobal(name, Int8(0), isExported = true)

                context.llvm.usedGlobals += global.llvmGlobal
                LLVMSetVisibility(global.llvmGlobal, LLVMVisibility.LLVMHiddenVisibility)
            }
        }
    }

    // TODO: consider including this into ObjCExportCodeSpec.
    private val objCClassForAny = ObjCClassForKotlinClass(
            namer.kotlinAnyName.binaryName,
            symbols.any,
            methods = listOf("equals", "hashCode", "toString").map { nameString ->
                val name = Name.identifier(nameString)

                val irFunction = symbols.any.owner.simpleFunctions().single { it.name == name }

                val descriptor = context.builtIns.any.unsubstitutedMemberScope
                        .getContributedFunctions(name, NoLookupLocation.FROM_BACKEND).single()

                val baseMethod = createObjCMethodSpecBaseMethod(mapper, namer, irFunction.symbol, descriptor)
                ObjCMethodForKotlinMethod(baseMethod)
            },
            categoryMethods = emptyList(),
            superClassNotAny = null
    )

    private fun emitSelectorsHolder() {
        val impType = functionType(voidType, false, int8TypePtr, int8TypePtr)
        val imp = generateFunctionNoRuntime(codegen, impType, "") {
            unreachable()
        }

        val methods = selectorsToDefine.map { (selector, bridge) ->
            ObjCDataGenerator.Method(selector, getEncoding(bridge), constPointer(imp))
        }

        dataGenerator.emitClass(
                "${namer.topLevelNamePrefix}KotlinSelectorsHolder",
                superName = "NSObject",
                instanceMethods = methods
        )
    }

    private val impType = pointerType(functionType(voidType, false))

    internal val directMethodAdapters = mutableMapOf<DirectAdapterRequest, ObjCToKotlinMethodAdapter>()

    internal val exceptionTypeInfoArrays = mutableMapOf<IrFunction, ConstPointer>()
    internal val typeInfoArrays = mutableMapOf<Set<IrClass>, ConstPointer>()

    inner class ObjCToKotlinMethodAdapter(
            selector: String,
            encoding: String,
            imp: ConstPointer
    ) : Struct(
            runtime.objCToKotlinMethodAdapter,
            staticData.cStringLiteral(selector),
            staticData.cStringLiteral(encoding),
            imp.bitcast(impType)
    )

    inner class KotlinToObjCMethodAdapter(
            selector: String,
            itablePlace: ClassLayoutBuilder.InterfaceTablePlace,
            vtableIndex: Int,
            kotlinImpl: ConstPointer
    ) : Struct(
            runtime.kotlinToObjCMethodAdapter,
            staticData.cStringLiteral(selector),
            Int32(itablePlace.interfaceId),
            Int32(itablePlace.itableSize),
            Int32(itablePlace.methodIndex),
            Int32(vtableIndex),
            kotlinImpl
    )

    inner class ObjCTypeAdapter(
            val irClass: IrClass?,
            typeInfo: ConstPointer?,
            vtable: ConstPointer?,
            vtableSize: Int,
            itable: List<RTTIGenerator.InterfaceTableRecord>,
            itableSize: Int,
            val objCName: String,
            directAdapters: List<ObjCToKotlinMethodAdapter>,
            classAdapters: List<ObjCToKotlinMethodAdapter>,
            virtualAdapters: List<ObjCToKotlinMethodAdapter>,
            reverseAdapters: List<KotlinToObjCMethodAdapter>
    ) : Struct(
            runtime.objCTypeAdapter,
            typeInfo,

            vtable,
            Int32(vtableSize),

            staticData.placeGlobalConstArray("", runtime.interfaceTableRecordType, itable),
            Int32(itableSize),

            staticData.cStringLiteral(objCName),

            staticData.placeGlobalConstArray(
                    "",
                    runtime.objCToKotlinMethodAdapter,
                    directAdapters
            ),
            Int32(directAdapters.size),

            staticData.placeGlobalConstArray(
                    "",
                    runtime.objCToKotlinMethodAdapter,
                    classAdapters
            ),
            Int32(classAdapters.size),

            staticData.placeGlobalConstArray(
                    "",
                    runtime.objCToKotlinMethodAdapter,
                    virtualAdapters
            ),
            Int32(virtualAdapters.size),

            staticData.placeGlobalConstArray(
                    "",
                    runtime.kotlinToObjCMethodAdapter,
                    reverseAdapters
            ),
            Int32(reverseAdapters.size)
    )

}

private fun ObjCExportCodeGenerator.replaceExternalWeakOrCommonGlobal(
        name: String,
        value: ConstValue,
        origin: CompiledKlibModuleOrigin
) {
    // TODO: A similar mechanism is used in `IrToBitcode.overrideRuntimeGlobal`. Consider merging them.
    if (context.llvmModuleSpecification.importsKotlinDeclarationsFromOtherSharedLibraries()) {
        val global = codegen.importGlobal(name, value.llvmType, origin)
        externalGlobalInitializers[global] = value
    } else {
        context.llvmImports.add(origin)
        val global = staticData.placeGlobal(name, value, isExported = true)

        if (context.llvmModuleSpecification.importsKotlinDeclarationsFromOtherObjectFiles()) {
            // Note: actually this is required only if global's weak/common definition is in another object file,
            // but it is simpler to do this for all globals, considering that all usages can't be removed by DCE anyway.
            context.llvm.usedGlobals += global.llvmGlobal
            LLVMSetVisibility(global.llvmGlobal, LLVMVisibility.LLVMHiddenVisibility)

            // See also [emitKt42254Hint].
        }
    }
}

private fun ObjCExportCodeGenerator.setObjCExportTypeInfo(
        irClass: IrClass,
        convertToRetained: ConstPointer? = null,
        objCClass: ConstPointer? = null,
        typeAdapter: ConstPointer? = null
) {
    val writableTypeInfoValue = buildWritableTypeInfoValue(
            convertToRetained = convertToRetained,
            objCClass = objCClass,
            typeAdapter = typeAdapter
    )

    if (codegen.isExternal(irClass)) {
        // Note: this global replaces the external one with common linkage.
        replaceExternalWeakOrCommonGlobal(
                irClass.writableTypeInfoSymbolName,
                writableTypeInfoValue,
                irClass.llvmSymbolOrigin
        )
    } else {
        setOwnWritableTypeInfo(irClass, writableTypeInfoValue)
    }
}

private fun ObjCExportCodeGeneratorBase.setOwnWritableTypeInfo(irClass: IrClass, writableTypeInfoValue: Struct) {
    require(!codegen.isExternal(irClass))
    val writeableTypeInfoGlobal = context.llvmDeclarations.forClass(irClass).writableTypeInfoGlobal!!
    writeableTypeInfoGlobal.setLinkage(LLVMLinkage.LLVMExternalLinkage)
    writeableTypeInfoGlobal.setInitializer(writableTypeInfoValue)
}

private fun ObjCExportCodeGeneratorBase.buildWritableTypeInfoValue(
        convertToRetained: ConstPointer? = null,
        objCClass: ConstPointer? = null,
        typeAdapter: ConstPointer? = null
): Struct {
    if (convertToRetained != null) {
        val expectedType = pointerType(functionType(int8TypePtr, false, codegen.kObjHeaderPtr))
        assert(convertToRetained.llvmType == expectedType) {
            "Expected: ${LLVMPrintTypeToString(expectedType)!!.toKString()} " +
                    "found: ${LLVMPrintTypeToString(convertToRetained.llvmType)!!.toKString()}"
        }
    }

    val objCExportAddition = Struct(runtime.typeInfoObjCExportAddition,
            convertToRetained?.bitcast(int8TypePtr),
            objCClass,
            typeAdapter
    )

    val writableTypeInfoType = runtime.writableTypeInfoType!!
    return Struct(writableTypeInfoType, objCExportAddition)
}

private val ObjCExportCodeGenerator.kotlinToObjCFunctionType: LlvmFunctionSignature
    get() = LlvmFunctionSignature(LlvmRetType(int8TypePtr), listOf(LlvmParamType(codegen.kObjHeaderPtr)), isVararg = false)

private val ObjCExportCodeGeneratorBase.objCToKotlinFunctionType: LLVMTypeRef
    get() = functionType(codegen.kObjHeaderPtr, false, int8TypePtr, codegen.kObjHeaderPtrPtr)

private fun ObjCExportCodeGenerator.emitBoxConverters() {
    val irBuiltIns = context.irBuiltIns

    emitBoxConverter(irBuiltIns.booleanClass, ObjCValueType.BOOL, "initWithBool:")
    emitBoxConverter(irBuiltIns.byteClass, ObjCValueType.CHAR, "initWithChar:")
    emitBoxConverter(irBuiltIns.shortClass, ObjCValueType.SHORT, "initWithShort:")
    emitBoxConverter(irBuiltIns.intClass, ObjCValueType.INT, "initWithInt:")
    emitBoxConverter(irBuiltIns.longClass, ObjCValueType.LONG_LONG, "initWithLongLong:")
    emitBoxConverter(symbols.uByte!!, ObjCValueType.UNSIGNED_CHAR, "initWithUnsignedChar:")
    emitBoxConverter(symbols.uShort!!, ObjCValueType.UNSIGNED_SHORT, "initWithUnsignedShort:")
    emitBoxConverter(symbols.uInt!!, ObjCValueType.UNSIGNED_INT, "initWithUnsignedInt:")
    emitBoxConverter(symbols.uLong!!, ObjCValueType.UNSIGNED_LONG_LONG, "initWithUnsignedLongLong:")
    emitBoxConverter(irBuiltIns.floatClass, ObjCValueType.FLOAT, "initWithFloat:")
    emitBoxConverter(irBuiltIns.doubleClass, ObjCValueType.DOUBLE, "initWithDouble:")
}

private fun ObjCExportCodeGenerator.emitBoxConverter(
        boxClassSymbol: IrClassSymbol,
        objCValueType: ObjCValueType,
        nsNumberInitSelector: String
) {
    val boxClass = boxClassSymbol.owner
    val name = "${boxClass.name}ToNSNumber"

    val converter = functionGenerator(kotlinToObjCFunctionType, name).generate {
        val unboxFunction = context.getUnboxFunction(boxClass).llvmFunction
        val kotlinValue = callFromBridge(
                unboxFunction,
                listOf(param(0)),
                Lifetime.IRRELEVANT
        )

        val value = kotlinToObjC(kotlinValue, objCValueType)
        val valueParameterTypes: List<LlvmParamType> = listOf(
                LlvmParamType(value.type, objCValueType.defaultParameterAttributes)
        )
        val nsNumberSubclass = genGetLinkedClass(namer.numberBoxName(boxClass.classId!!).binaryName)
        val switchToNative = false // We consider these methods fast enough.
        val instance = callFromBridge(objcAlloc, listOf(nsNumberSubclass), toNative = switchToNative)
        val returnType = LlvmRetType(int8TypePtr)
        ret(genSendMessage(returnType, valueParameterTypes, instance, nsNumberInitSelector, switchToNative, value))
    }

    LLVMSetLinkage(converter, LLVMLinkage.LLVMPrivateLinkage)
    setObjCExportTypeInfo(boxClass, constPointer(converter))
}

private fun ObjCExportCodeGenerator.generateContinuationToRetainedCompletionConverter(
        blockGenerator: BlockGenerator
): LLVMValueRef = with(blockGenerator) {
    generateWrapKotlinObjectToRetainedBlock(
            BlockType(numberOfParameters = 2, returnsVoid = true),
            convertName = "convertContinuation",
            invokeName = "invokeCompletion"
    ) { continuation, arguments ->
        check(arguments.size == 2)
        callFromBridge(context.llvm.Kotlin_ObjCExport_resumeContinuation, listOf(continuation) + arguments)
        ret(null)
    }
}

// TODO: find out what to use instead here and in the dependent code
private val ObjCExportBlockCodeGenerator.mappedFunctionNClasses get() =
    // failed attempt to migrate to descriptor-less IrBuiltIns
    ((context.irBuiltIns as IrBuiltInsOverDescriptors).functionFactory as BuiltInFictitiousFunctionIrClassFactory).builtFunctionNClasses
        .filter { it.descriptor.isMappedFunctionClass() }

private fun ObjCExportBlockCodeGenerator.emitFunctionConverters() {
    require(context.producedLlvmModuleContainsStdlib)
    mappedFunctionNClasses.forEach { functionClass ->
        val convertToRetained = kotlinFunctionToRetainedBlockConverter(BlockPointerBridge(functionClass.arity, returnsVoid = false))

        val writableTypeInfoValue = buildWritableTypeInfoValue(convertToRetained = constPointer(convertToRetained))
        setOwnWritableTypeInfo(functionClass.irClass, writableTypeInfoValue)
    }
}

private fun ObjCExportBlockCodeGenerator.emitBlockToKotlinFunctionConverters() {
    require(context.producedLlvmModuleContainsStdlib)
    val functionClassesByArity = mappedFunctionNClasses.associateBy { it.arity }

    val arityLimit = (functionClassesByArity.keys.maxOrNull() ?: -1) + 1

    val converters = (0 until arityLimit).map { arity ->
        functionClassesByArity[arity]?.let {
            val bridge = BlockPointerBridge(numberOfParameters = arity, returnsVoid = false)
            constPointer(blockToKotlinFunctionConverter(bridge))
        } ?: NullPointer(objCToKotlinFunctionType)
    }

    val ptr = staticData.placeGlobalArray(
            "",
            pointerType(objCToKotlinFunctionType),
            converters
    ).pointer.getElementPtr(0)

    // Note: defining globals declared in runtime.
    staticData.placeGlobal("Kotlin_ObjCExport_blockToFunctionConverters", ptr, isExported = true)
    staticData.placeGlobal("Kotlin_ObjCExport_blockToFunctionConverters_size", Int32(arityLimit), isExported = true)
}

private fun ObjCExportCodeGenerator.emitSpecialClassesConvertions() {
    setObjCExportTypeInfo(
            symbols.string.owner,
            constPointer(context.llvm.Kotlin_ObjCExport_CreateRetainedNSStringFromKString.llvmValue)
    )

    emitCollectionConverters()

    emitBoxConverters()
}

private fun ObjCExportCodeGenerator.emitCollectionConverters() {

    fun importConverter(name: String): ConstPointer = constPointer(context.llvm.externalFunction(LlvmFunctionProto(
            name,
            kotlinToObjCFunctionType,
            origin = CurrentKlibModuleOrigin
    )).llvmValue)

    setObjCExportTypeInfo(
            symbols.list.owner,
            importConverter("Kotlin_Interop_CreateRetainedNSArrayFromKList")
    )

    setObjCExportTypeInfo(
            symbols.mutableList.owner,
            importConverter("Kotlin_Interop_CreateRetainedNSMutableArrayFromKList")
    )

    setObjCExportTypeInfo(
            symbols.set.owner,
            importConverter("Kotlin_Interop_CreateRetainedNSSetFromKSet")
    )

    setObjCExportTypeInfo(
            symbols.mutableSet.owner,
            importConverter("Kotlin_Interop_CreateRetainedKotlinMutableSetFromKSet")
    )

    setObjCExportTypeInfo(
            symbols.map.owner,
            importConverter("Kotlin_Interop_CreateRetainedNSDictionaryFromKMap")
    )

    setObjCExportTypeInfo(
            symbols.mutableMap.owner,
            importConverter("Kotlin_Interop_CreateRetainedKotlinMutableDictionaryFromKMap")
    )
}

private fun ObjCExportFunctionGenerationContextBuilder.setupBridgeDebugInfo() {
    val location = setupBridgeDebugInfo(this.objCExportCodegen.context, function)
    startLocation = location
    endLocation = location
}

private inline fun ObjCExportCodeGenerator.generateObjCImpBy(
        methodBridge: MethodBridge,
        debugInfo: Boolean = false,
        suffix: String? = null,
        genBody: ObjCExportFunctionGenerationContext.() -> Unit
): LLVMValueRef {
    val functionType = objCFunctionType(context, methodBridge)
    val functionName = "objc2kotlin" + (suffix?.let { "_$it" } ?: "")
    val result = functionGenerator(functionType, functionName) {
        if (debugInfo) {
            this.setupBridgeDebugInfo()
        }

        switchToRunnable = true
    }.generate {
        genBody()
    }

    LLVMSetLinkage(result, LLVMLinkage.LLVMInternalLinkage)
    return result
}

private fun ObjCExportCodeGenerator.generateAbstractObjCImp(methodBridge: MethodBridge): LLVMValueRef =
        generateObjCImpBy(methodBridge) {
            callFromBridge(
                    context.llvm.Kotlin_ObjCExport_AbstractMethodCalled,
                    listOf(param(0), param(1))
            )
            unreachable()
        }

private fun ObjCExportCodeGenerator.generateObjCImp(
        target: IrFunction?,
        baseMethod: IrFunction,
        methodBridge: MethodBridge,
        isVirtual: Boolean = false
) = if (target == null) {
    generateAbstractObjCImp(methodBridge)
} else {
    generateObjCImp(
            methodBridge,
            isDirect = !isVirtual,
            baseMethod = baseMethod
    ) { args, resultLifetime, exceptionHandler ->
        if (target is IrConstructor && target.constructedClass.isAbstract()) {
            callFromBridge(
                    context.llvm.Kotlin_ObjCExport_AbstractClassConstructorCalled,
                    listOf(param(0), codegen.typeInfoValue(target.parent as IrClass))
            )
        }
        val llvmCallable = if (!isVirtual) {
            codegen.llvmFunction(target)
        } else {
            lookupVirtualImpl(args.first(), target)
        }
        call(llvmCallable, args, resultLifetime, exceptionHandler)
    }
}

private fun ObjCExportCodeGenerator.generateObjCImp(
        methodBridge: MethodBridge,
        isDirect: Boolean,
        baseMethod: IrFunction? = null,
        callKotlin: FunctionGenerationContext.(
                args: List<LLVMValueRef>,
                resultLifetime: Lifetime,
                exceptionHandler: ExceptionHandler
        ) -> LLVMValueRef?
): LLVMValueRef = generateObjCImpBy(
        methodBridge,
        debugInfo = isDirect /* see below */,
        suffix = meaningfulBridgeNameOrNull(baseMethod)
) {
    // Considering direct calls inlinable above. If such a call is inlined into a bridge with no debug information,
    // lldb will not decode the inlined frame even if the callee has debug information.
    // So generate dummy debug information for bridge in this case.
    // TODO: consider adding debug info to other bridges.

    val returnType = methodBridge.returnBridge

    // TODO: call [NSObject init] if it is a constructor?
    // TODO: check for abstract class if it is a constructor.

    if (!methodBridge.isInstance) {
        initRuntimeIfNeeded() // For instance methods it gets called when allocating.
    }

    var errorOutPtr: LLVMValueRef? = null
    var continuation: LLVMValueRef? = null

    val kotlinArgs = methodBridge.paramBridges.mapIndexedNotNull { index, paramBridge ->
        val parameter = param(index)
        when (paramBridge) {
            is MethodBridgeValueParameter.Mapped ->
                objCToKotlin(parameter, paramBridge.bridge, Lifetime.ARGUMENT)

            MethodBridgeReceiver.Static, MethodBridgeSelector -> null
            MethodBridgeReceiver.Instance -> objCReferenceToKotlin(parameter, Lifetime.ARGUMENT)

            MethodBridgeReceiver.Factory -> null // actual value added by [callKotlin].

            MethodBridgeValueParameter.ErrorOutParameter -> {
                assert(errorOutPtr == null)
                errorOutPtr = parameter
                null
            }

            MethodBridgeValueParameter.SuspendCompletion -> {
                callFromBridge(
                        context.llvm.Kotlin_ObjCExport_createContinuationArgument,
                        listOf(parameter, generateExceptionTypeInfoArray(baseMethod!!)),
                        Lifetime.ARGUMENT
                ).also {
                    continuation = it
                }
            }
        }
    }

    // TODO: consider merging this handler with function cleanup.
    val exceptionHandler = when {
        errorOutPtr != null -> kotlinExceptionHandler { exception ->
            callFromBridge(
                    context.llvm.Kotlin_ObjCExport_RethrowExceptionAsNSError,
                    listOf(exception, errorOutPtr!!, generateExceptionTypeInfoArray(baseMethod!!))
            )

            val returnValue = when (returnType) {
                !is MethodBridge.ReturnValue.WithError ->
                    error("bridge with error parameter has unexpected return type: $returnType")

                MethodBridge.ReturnValue.WithError.Success -> Int8(0).llvm // false

                is MethodBridge.ReturnValue.WithError.ZeroForError -> {
                    if (returnType.successBridge == MethodBridge.ReturnValue.Instance.InitResult) {
                        // Release init receiver, as required by convention.
                        callFromBridge(objcRelease, listOf(param(0)), toNative = true)
                    }
                    Zero(returnType.toLlvmRetType(context).llvmType).llvm
                }
            }

            ret(returnValue)
        }

        continuation != null -> kotlinExceptionHandler { exception ->
            // Callee haven't suspended, so it isn't going to call the completion. Call it here:
            callFromBridge(
                    context.ir.symbols.objCExportResumeContinuationWithException.owner.llvmFunction,
                    listOf(continuation!!, exception)
            )
            // Note: completion block could be called directly instead, but this implementation is
            // simpler and avoids duplication.
            ret(null)
        }

        else -> kotlinExceptionHandler { exception ->
            callFromBridge(symbols.objCExportTrapOnUndeclaredException.owner.llvmFunction, listOf(exception))
            unreachable()
        }
    }

    val targetResult = callKotlin(kotlinArgs, Lifetime.ARGUMENT, exceptionHandler)

    tailrec fun genReturnOnSuccess(returnBridge: MethodBridge.ReturnValue) {
        val returnValue: LLVMValueRef? = when (returnBridge) {
            MethodBridge.ReturnValue.Void -> null
            MethodBridge.ReturnValue.HashCode -> {
                val kotlinHashCode = targetResult!!
                if (codegen.context.is64BitNSInteger()) zext(kotlinHashCode, int64Type) else kotlinHashCode
            }
            is MethodBridge.ReturnValue.Mapped -> if (LLVMTypeOf(targetResult!!) == voidType) {
                returnBridge.bridge.makeNothing()
            } else {
                when (returnBridge.bridge) {
                    is ReferenceBridge -> return autoreleaseAndRet(kotlinReferenceToRetainedObjC(targetResult))
                    is BlockPointerBridge -> return autoreleaseAndRet(kotlinFunctionToRetainedObjCBlockPointer(returnBridge.bridge, targetResult))
                    is ValueTypeBridge -> kotlinToObjC(targetResult, returnBridge.bridge.objCValueType)
                }
            }
            MethodBridge.ReturnValue.WithError.Success -> Int8(1).llvm // true
            is MethodBridge.ReturnValue.WithError.ZeroForError -> return genReturnOnSuccess(returnBridge.successBridge)
            MethodBridge.ReturnValue.Instance.InitResult -> param(0)
            MethodBridge.ReturnValue.Instance.FactoryResult -> return autoreleaseAndRet(kotlinReferenceToRetainedObjC(targetResult!!)) // provided by [callKotlin]
            MethodBridge.ReturnValue.Suspend -> {
                val coroutineSuspended = callFromBridge(
                        codegen.llvmFunction(context.ir.symbols.objCExportGetCoroutineSuspended.owner),
                        emptyList(),
                        Lifetime.STACK
                )
                ifThen(icmpNe(targetResult!!, coroutineSuspended)) {
                    // Callee haven't suspended, so it isn't going to call the completion. Call it here:
                    callFromBridge(
                            context.ir.symbols.objCExportResumeContinuation.owner.llvmFunction,
                            listOf(continuation!!, targetResult)
                    )
                    // Note: completion block could be called directly instead, but this implementation is
                    // simpler and avoids duplication.
                }
                null
            }
        }

        // Note: some branches above don't reach here, because emit their own optimized return code.
        ret(returnValue)
    }

    genReturnOnSuccess(returnType)
}

private fun ObjCExportCodeGenerator.generateExceptionTypeInfoArray(baseMethod: IrFunction): LLVMValueRef =
        exceptionTypeInfoArrays.getOrPut(baseMethod) {
            val types = effectiveThrowsClasses(baseMethod, symbols)
            generateTypeInfoArray(types.toSet())
        }.llvm

private fun ObjCExportCodeGenerator.generateTypeInfoArray(types: Set<IrClass>): ConstPointer =
        typeInfoArrays.getOrPut(types) {
            val typeInfos = types.map { with(codegen) { it.typeInfoPtr } } + NullPointer(codegen.kTypeInfo)
            codegen.staticData.placeGlobalConstArray("", codegen.kTypeInfoPtr, typeInfos)
        }

private fun effectiveThrowsClasses(method: IrFunction, symbols: KonanSymbols): List<IrClass> {
    if (method is IrSimpleFunction && method.overriddenSymbols.isNotEmpty()) {
        return effectiveThrowsClasses(method.overriddenSymbols.first().owner, symbols)
    }

    val throwsAnnotation = method.annotations.findAnnotation(KonanFqNames.throws)
            ?: return if (method.isSuspend) {
                listOf(symbols.cancellationException.owner)
            } else {
                // Note: frontend ensures that all topmost overridden methods have (equal) @Throws annotations.
                // However due to linking different versions of libraries IR could end up not meeting this condition.
                // Handling missing annotation gracefully:
                emptyList()
            }

    val throwsVararg = throwsAnnotation.getValueArgument(0)
            ?: return emptyList()

    if (throwsVararg !is IrVararg) error(method.getContainingFile(), throwsVararg, "unexpected vararg")

    return throwsVararg.elements.map {
        (it as? IrClassReference)?.symbol?.owner as? IrClass
                ?: error(method.getContainingFile(), it, "unexpected @Throws argument")
    }
}

private fun ObjCExportCodeGenerator.generateObjCImpForArrayConstructor(
        target: IrConstructor,
        methodBridge: MethodBridge
): LLVMValueRef = generateObjCImp(methodBridge, isDirect = true) { args, resultLifetime, exceptionHandler ->
    val arrayInstance = callFromBridge(
            context.llvm.allocArrayFunction,
            listOf(target.constructedClass.llvmTypeInfoPtr, args.first()),
            resultLifetime = Lifetime.ARGUMENT
    )

    call(target.llvmFunction, listOf(arrayInstance) + args, resultLifetime, exceptionHandler)
    arrayInstance
}

// TODO: cache bridges.
private fun ObjCExportCodeGenerator.generateKotlinToObjCBridge(
        irFunction: IrFunction,
        baseMethod: ObjCMethodSpec.BaseMethod<IrSimpleFunctionSymbol>
): ConstPointer {
    val baseIrFunction = baseMethod.symbol.owner

    val methodBridge = baseMethod.bridge

    val parameterToBase = irFunction.allParameters.zip(baseIrFunction.allParameters).toMap()

    val objcMsgSend = msgSender(objCFunctionType(context, methodBridge))

    val functionType = LlvmFunctionSignature(irFunction, codegen)

    val result = functionGenerator(functionType, "kotlin2objc").generate {
        var errorOutPtr: LLVMValueRef? = null

        val parameters = irFunction.allParameters.mapIndexed { index, parameterDescriptor ->
            parameterDescriptor to param(index)
        }.toMap()

        val objCArgs = methodBridge.parametersAssociated(irFunction).map { (bridge, parameter) ->
            when (bridge) {
                is MethodBridgeValueParameter.Mapped -> {
                    parameter!!
                    val kotlinValue = convertKotlin(
                            { parameters[parameter]!! },
                            actualType = parameter.type,
                            expectedType = parameterToBase[parameter]!!.type,
                            resultLifetime = Lifetime.ARGUMENT
                    )
                    kotlinToLocalObjC(kotlinValue, bridge.bridge)
                }

                MethodBridgeReceiver.Instance -> kotlinReferenceToLocalObjC(parameters[parameter]!!)
                MethodBridgeSelector -> {
                    val selector = baseMethod.selector
                    // Selector is referenced thus should be defined to avoid false positive non-public API rejection:
                    selectorsToDefine[selector] = methodBridge
                    genSelector(selector)
                }

                MethodBridgeReceiver.Static,
                MethodBridgeReceiver.Factory ->
                    error("Method is not instance and thus can't have bridge for overriding: $baseMethod")

                MethodBridgeValueParameter.ErrorOutParameter ->
                    alloca(int8TypePtr).also {
                        store(kNullInt8Ptr, it)
                        errorOutPtr = it
                    }

                MethodBridgeValueParameter.SuspendCompletion -> {
                    val continuation = param(irFunction.allParametersCount) // The last argument.
                    // TODO: consider placing interception into the converter to reduce code size.
                    val intercepted = callFromBridge(
                            context.ir.symbols.objCExportInterceptedContinuation.owner.llvmFunction,
                            listOf(continuation),
                            Lifetime.ARGUMENT
                    )
                    val retainedCompletion = callFromBridge(continuationToRetainedCompletionConverter, listOf(intercepted))
                    callFromBridge(objcAutorelease, listOf(retainedCompletion)) // TODO: use stack-allocated block here instead.
                }
            }
        }

        val targetResult = callFromBridge(objcMsgSend, objCArgs, toNative = true)

        assert(baseMethod.symbol !is IrConstructorSymbol)

        fun rethrow() {
            val error = load(errorOutPtr!!)
            callFromBridge(context.llvm.Kotlin_ObjCExport_RethrowNSErrorAsException, listOf(error))
            unreachable()
        }

        fun genKotlinBaseMethodResult(
                lifetime: Lifetime,
                returnBridge: MethodBridge.ReturnValue
        ): LLVMValueRef? = when (returnBridge) {
            MethodBridge.ReturnValue.Void -> null

            MethodBridge.ReturnValue.HashCode -> {
                if (codegen.context.is64BitNSInteger()) {
                    val low = trunc(targetResult, int32Type)
                    val high = trunc(shr(targetResult, 32, signed = false), int32Type)
                    xor(low, high)
                } else {
                    targetResult
                }
            }

            is MethodBridge.ReturnValue.Mapped -> {
                objCToKotlin(targetResult, returnBridge.bridge, lifetime)
            }

            MethodBridge.ReturnValue.WithError.Success -> {
                ifThen(icmpEq(targetResult, Int8(0).llvm)) {
                    rethrow()
                }
                null
            }

            is MethodBridge.ReturnValue.WithError.ZeroForError -> {
                if (returnBridge.successMayBeZero) {
                    val error = load(errorOutPtr!!)
                    ifThen(icmpNe(error, kNullInt8Ptr)) {
                        rethrow()
                    }
                } else {
                    ifThen(icmpEq(targetResult, kNullInt8Ptr)) {
                        rethrow()
                    }
                }
                genKotlinBaseMethodResult(lifetime, returnBridge.successBridge)
            }

            MethodBridge.ReturnValue.Instance.InitResult,
            MethodBridge.ReturnValue.Instance.FactoryResult ->
                error("init or factory method can't have bridge for overriding: $baseMethod")

            MethodBridge.ReturnValue.Suspend -> {
                // Objective-C implementation of Kotlin suspend function is always responsible
                // for calling the completion, so in Kotlin coroutines machinery terms it suspends,
                // which is indicated by the return value:
                callFromBridge(
                        context.ir.symbols.objCExportGetCoroutineSuspended.owner.llvmFunction,
                        emptyList(),
                        Lifetime.RETURN_VALUE
                )
            }
        }

        val baseReturnType = baseIrFunction.returnType
        val actualReturnType = irFunction.returnType

        val retVal = when {
            baseIrFunction.isSuspend -> genKotlinBaseMethodResult(Lifetime.RETURN_VALUE, methodBridge.returnBridge)

            actualReturnType.isUnit() || actualReturnType.isNothing() -> {
                genKotlinBaseMethodResult(Lifetime.ARGUMENT, methodBridge.returnBridge)
                null
            }
            baseReturnType.isUnit() || baseReturnType.isNothing() -> {
                genKotlinBaseMethodResult(Lifetime.ARGUMENT, methodBridge.returnBridge)
                codegen.theUnitInstanceRef.llvm
            }
            else ->
                convertKotlin(
                        { lifetime -> genKotlinBaseMethodResult(lifetime, methodBridge.returnBridge)!! },
                        actualType = baseReturnType,
                        expectedType = actualReturnType,
                        resultLifetime = Lifetime.RETURN_VALUE
                )
        }

        ret(retVal)
    }

    LLVMSetLinkage(result, LLVMLinkage.LLVMPrivateLinkage)

    return constPointer(result)
}

private fun ObjCExportCodeGenerator.createReverseAdapter(
        irFunction: IrFunction,
        baseMethod: ObjCMethodSpec.BaseMethod<IrSimpleFunctionSymbol>,
        vtableIndex: Int?,
        itablePlace: ClassLayoutBuilder.InterfaceTablePlace?
): ObjCExportCodeGenerator.KotlinToObjCMethodAdapter {

    val selector = baseMethod.selector

    val kotlinToObjC = generateKotlinToObjCBridge(
            irFunction,
            baseMethod
    ).bitcast(int8TypePtr)

    return KotlinToObjCMethodAdapter(selector,
            itablePlace ?: ClassLayoutBuilder.InterfaceTablePlace.INVALID,
            vtableIndex ?: -1,
            kotlinToObjC)
}

private fun ObjCExportCodeGenerator.createMethodVirtualAdapter(
        baseMethod: ObjCMethodSpec.BaseMethod<IrSimpleFunctionSymbol>
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    val selector = baseMethod.selector
    val methodBridge = baseMethod.bridge
    val irFunction = baseMethod.symbol.owner
    val imp = generateObjCImp(irFunction, irFunction, methodBridge, isVirtual = true)

    return objCToKotlinMethodAdapter(selector, methodBridge, imp)
}

private fun ObjCExportCodeGenerator.createMethodAdapter(
        implementation: IrFunction?,
        baseMethod: ObjCMethodSpec.BaseMethod<*>
) = createMethodAdapter(DirectAdapterRequest(implementation, baseMethod))

private fun ObjCExportCodeGenerator.createFinalMethodAdapter(
        baseMethod: ObjCMethodSpec.BaseMethod<IrSimpleFunctionSymbol>
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    val irFunction = baseMethod.symbol.owner
    require(irFunction.modality == Modality.FINAL)
    return createMethodAdapter(irFunction, baseMethod)
}

private fun ObjCExportCodeGenerator.createMethodAdapter(
        request: DirectAdapterRequest
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter = this.directMethodAdapters.getOrPut(request) {

    val selectorName = request.base.selector
    val methodBridge = request.base.bridge

    val imp = generateObjCImp(request.implementation, request.base.symbol.owner, methodBridge)

    objCToKotlinMethodAdapter(selectorName, methodBridge, imp)
}

private fun ObjCExportCodeGenerator.createConstructorAdapter(
        baseMethod: ObjCMethodSpec.BaseMethod<IrConstructorSymbol>
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter = createMethodAdapter(baseMethod.symbol.owner, baseMethod)

private fun ObjCExportCodeGenerator.createArrayConstructorAdapter(
        baseMethod: ObjCMethodSpec.BaseMethod<IrConstructorSymbol>
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    val selectorName = baseMethod.selector
    val methodBridge = baseMethod.bridge
    val irConstructor = baseMethod.symbol.owner
    val imp = generateObjCImpForArrayConstructor(irConstructor, methodBridge)

    return objCToKotlinMethodAdapter(selectorName, methodBridge, imp)
}

private fun ObjCExportCodeGenerator.vtableIndex(irFunction: IrSimpleFunction): Int? {
    assert(irFunction.isOverridable)
    val irClass = irFunction.parentAsClass
    return if (irClass.isInterface) {
        null
    } else {
        context.getLayoutBuilder(irClass).vtableIndex(irFunction)
    }
}

private fun ObjCExportCodeGenerator.itablePlace(irFunction: IrSimpleFunction): ClassLayoutBuilder.InterfaceTablePlace? {
    assert(irFunction.isOverridable)
    val irClass = irFunction.parentAsClass
    return if (irClass.isInterface
            && (irFunction.isReal || irFunction.resolveFakeOverrideMaybeAbstract().parent != context.irBuiltIns.anyClass.owner)
    ) {
        context.getLayoutBuilder(irClass).itablePlace(irFunction)
    } else {
        null
    }
}

private fun ObjCExportCodeGenerator.createTypeAdapterForFileClass(
        fileClass: ObjCClassForKotlinFile
): ObjCExportCodeGenerator.ObjCTypeAdapter {
    val name = fileClass.binaryName

    val adapters = fileClass.methods.map { createFinalMethodAdapter(it.baseMethod) }

    return ObjCTypeAdapter(
            irClass = null,
            typeInfo = null,
            vtable = null,
            vtableSize = -1,
            itable = emptyList(),
            itableSize = -1,
            objCName = name,
            directAdapters = emptyList(),
            classAdapters = adapters,
            virtualAdapters = emptyList(),
            reverseAdapters = emptyList()
    )
}

private fun ObjCExportCodeGenerator.createTypeAdapter(
        type: ObjCTypeForKotlinType,
        superClass: ObjCClassForKotlinClass?,
        reverseAdapters: List<ObjCExportCodeGenerator.KotlinToObjCMethodAdapter>
): ObjCExportCodeGenerator.ObjCTypeAdapter {
    val irClass = type.irClassSymbol.owner
    val adapters = mutableListOf<ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter>()
    val classAdapters = mutableListOf<ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter>()

    type.methods.forEach {
        when (it) {
            is ObjCInitMethodForKotlinConstructor -> {
                adapters += createConstructorAdapter(it.baseMethod)
            }
            is ObjCFactoryMethodForKotlinArrayConstructor -> {
                classAdapters += createArrayConstructorAdapter(it.baseMethod)
            }
            is ObjCGetterForKotlinEnumEntry -> {
                classAdapters += createEnumEntryAdapter(it.irEnumEntrySymbol.owner, it.selector)
            }
            is ObjCClassMethodForKotlinEnumValues -> {
                classAdapters += createEnumValuesAdapter(it.valuesFunctionSymbol.owner, it.selector)
            }
            is ObjCGetterForObjectInstance -> {
                classAdapters += if (it.classSymbol.owner.isUnit()) {
                    createUnitInstanceAdapter(it.selector)
                } else {
                    createObjectInstanceAdapter(it.classSymbol.owner, it.selector)
                }
            }
            ObjCKotlinThrowableAsErrorMethod -> {
                adapters += createThrowableAsErrorAdapter()
            }
            is ObjCMethodForKotlinMethod -> {} // Handled below.
        }.let {} // Force exhaustive.
    }

    val additionalReverseAdapters = mutableListOf<ObjCExportCodeGenerator.KotlinToObjCMethodAdapter>()

    if (type is ObjCClassForKotlinClass) {

        type.categoryMethods.forEach {
            adapters += createFinalMethodAdapter(it.baseMethod)
            additionalReverseAdapters += nonOverridableAdapter(it.baseMethod.selector, hasSelectorAmbiguity = false)
        }

        adapters += createDirectAdapters(type, superClass)
    }

    val virtualAdapters = type.kotlinMethods
            .filter {
                val irFunction = it.baseMethod.symbol.owner
                irFunction.parentAsClass == irClass && irFunction.isOverridable
            }.map { createMethodVirtualAdapter(it.baseMethod) }

    val typeInfo = constPointer(codegen.typeInfoValue(irClass))
    val objCName = type.binaryName

    val vtableSize = if (irClass.kind == ClassKind.INTERFACE) {
        -1
    } else {
        context.getLayoutBuilder(irClass).vtableEntries.size
    }

    val vtable = if (!irClass.isInterface && !irClass.typeInfoHasVtableAttached) {
        staticData.placeGlobal("", rttiGenerator.vtable(irClass)).also {
            it.setConstant(true)
        }.pointer.getElementPtr(0)
    } else {
        null
    }

    val (itable, itableSize) = when {
        irClass.isInterface -> Pair(emptyList(), context.getLayoutBuilder(irClass).interfaceVTableEntries.size)
        irClass.isAbstract() -> rttiGenerator.interfaceTableRecords(irClass)
        else -> Pair(emptyList(), -1)
    }

    return ObjCTypeAdapter(
            irClass,
            typeInfo,
            vtable,
            vtableSize,
            itable,
            itableSize,
            objCName,
            adapters,
            classAdapters,
            virtualAdapters,
            reverseAdapters + additionalReverseAdapters
    )
}

private fun ObjCExportCodeGenerator.createReverseAdapters(
        types: List<ObjCTypeForKotlinType>
): Map<ObjCTypeForKotlinType, ReverseAdapters> {
    val irClassSymbolToType = types.associateBy { it.irClassSymbol }

    val result = mutableMapOf<ObjCTypeForKotlinType, ReverseAdapters>()

    fun getOrCreateFor(type: ObjCTypeForKotlinType): ReverseAdapters = result.getOrPut(type) {
        // Each type also inherits reverse adapters from super types.
        // This is handled in runtime when building TypeInfo for Swift or Obj-C type
        // subclassing Kotlin classes or interfaces. See [createTypeInfo] in ObjCExport.mm.
        val allSuperClasses = DFS.dfs(
                type.irClassSymbol.owner.superClasses,
                { it.owner.superClasses },
                object : DFS.NodeHandlerWithListResult<IrClassSymbol, IrClassSymbol>() {
                    override fun afterChildren(current: IrClassSymbol) {
                        this.result += current
                    }
                }
        )

        val inheritsAdaptersFrom = allSuperClasses.mapNotNull { irClassSymbolToType[it] }

        val inheritedAdapters = inheritsAdaptersFrom.map { getOrCreateFor(it) }

        createReverseAdapters(type, inheritedAdapters)
    }

    types.forEach { getOrCreateFor(it) }

    return result
}

private class ReverseAdapters(
        val adapters: List<ObjCExportCodeGenerator.KotlinToObjCMethodAdapter>,
        val coveredMethods: Set<IrSimpleFunction>
)

private fun ObjCExportCodeGenerator.createReverseAdapters(
        type: ObjCTypeForKotlinType,
        inheritedAdapters: List<ReverseAdapters>
): ReverseAdapters {
    val result = mutableListOf<ObjCExportCodeGenerator.KotlinToObjCMethodAdapter>()
    val coveredMethods = mutableSetOf<IrSimpleFunction>()

    val methodsCoveredByInheritedAdapters = inheritedAdapters.flatMapTo(mutableSetOf()) { it.coveredMethods }

    val allBaseMethodsByIr = type.kotlinMethods.map { it.baseMethod }.associateBy { it.symbol.owner }

    for (method in type.irClassSymbol.owner.simpleFunctions()) {
        val baseMethods = method.allOverriddenFunctions.mapNotNull { allBaseMethodsByIr[it] }
        if (baseMethods.isEmpty()) continue

        val hasSelectorAmbiguity = baseMethods.map { it.selector }.distinct().size > 1

        if (method.isOverridable && !hasSelectorAmbiguity) {
            val baseMethod = baseMethods.first()

            val presentVtableBridges = mutableSetOf<Int?>(null)
            val presentMethodTableBridges = mutableSetOf<String>()
            val presentItableBridges = mutableSetOf<ClassLayoutBuilder.InterfaceTablePlace?>(null)

            val allOverriddenMethods = method.allOverriddenFunctions

            val (inherited, uninherited) = allOverriddenMethods.partition {
                it in methodsCoveredByInheritedAdapters
            }

            inherited.forEach {
                presentVtableBridges += vtableIndex(it)
                presentMethodTableBridges += it.computeFunctionName()
                presentItableBridges += itablePlace(it)
            }

            uninherited.forEach {
                val vtableIndex = vtableIndex(it)
                val functionName = it.computeFunctionName()
                val itablePlace = itablePlace(it)

                if (vtableIndex !in presentVtableBridges || functionName !in presentMethodTableBridges
                        || itablePlace !in presentItableBridges) {
                    presentVtableBridges += vtableIndex
                    presentMethodTableBridges += functionName
                    presentItableBridges += itablePlace
                    result += createReverseAdapter(it, baseMethod, vtableIndex, itablePlace)
                    coveredMethods += it
                }
            }

        } else {
            // Mark it as non-overridable:
            baseMethods.map { it.selector }.distinct().forEach {
                result += nonOverridableAdapter(it, hasSelectorAmbiguity)
            }
        }
    }

    return ReverseAdapters(result, coveredMethods)
}

private fun ObjCExportCodeGenerator.nonOverridableAdapter(
        selector: String,
        hasSelectorAmbiguity: Boolean
): ObjCExportCodeGenerator.KotlinToObjCMethodAdapter = KotlinToObjCMethodAdapter(
    selector,
    vtableIndex = if (hasSelectorAmbiguity) -2 else -1, // Describes the reason.
    kotlinImpl = NullPointer(int8Type),
    itablePlace = ClassLayoutBuilder.InterfaceTablePlace.INVALID
)

private val ObjCTypeForKotlinType.kotlinMethods: List<ObjCMethodForKotlinMethod>
    get() = this.methods.filterIsInstance<ObjCMethodForKotlinMethod>()

internal data class DirectAdapterRequest(val implementation: IrFunction?, val base: ObjCMethodSpec.BaseMethod<*>)

private fun ObjCExportCodeGenerator.createDirectAdapters(
        typeDeclaration: ObjCClassForKotlinClass,
        superClass: ObjCClassForKotlinClass?
): List<ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter> {

    fun ObjCClassForKotlinClass.getAllRequiredDirectAdapters() = this.kotlinMethods.map { method ->
        DirectAdapterRequest(
                findImplementation(irClassSymbol.owner, method.baseMethod.symbol.owner, context),
                method.baseMethod
        )
    }

    val inheritedAdapters = superClass?.getAllRequiredDirectAdapters().orEmpty()
    val requiredAdapters = typeDeclaration.getAllRequiredDirectAdapters() - inheritedAdapters

    return requiredAdapters.distinctBy { it.base.selector }.map { createMethodAdapter(it) }
}

private fun findImplementation(irClass: IrClass, method: IrSimpleFunction, context: Context): IrSimpleFunction? {
    val override = irClass.simpleFunctions().singleOrNull {
        method in it.allOverriddenFunctions
    } ?: error("no implementation for ${method.render()}\nin ${irClass.fqNameWhenAvailable}")
    return OverriddenFunctionInfo(override, method).getImplementation(context)
}

private inline fun ObjCExportCodeGenerator.generateObjCToKotlinSyntheticGetter(
        selector: String,
        block: ObjCExportFunctionGenerationContext.() -> Unit
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {

    val methodBridge = MethodBridge(
            MethodBridge.ReturnValue.Mapped(ReferenceBridge),
            MethodBridgeReceiver.Static, valueParameters = emptyList()
    )

    val functionType = objCFunctionType(context, methodBridge)
    val imp = functionGenerator(functionType, "objc2kotlin") {
        switchToRunnable = true
    }.generate {
        block()
    }

    LLVMSetLinkage(imp, LLVMLinkage.LLVMPrivateLinkage)

    return objCToKotlinMethodAdapter(selector, methodBridge, imp)
}

private fun ObjCExportCodeGenerator.objCToKotlinMethodAdapter(
        selector: String,
        methodBridge: MethodBridge,
        imp: LLVMValueRef
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    selectorsToDefine[selector] = methodBridge

    return ObjCToKotlinMethodAdapter(selector, getEncoding(methodBridge), constPointer(imp))
}

private fun ObjCExportCodeGenerator.createUnitInstanceAdapter(selector: String) =
        generateObjCToKotlinSyntheticGetter(selector) {
            // Note: generateObjCToKotlinSyntheticGetter switches to Runnable, which is probably not required here and thus suboptimal.
            initRuntimeIfNeeded() // For instance methods it gets called when allocating.

            autoreleaseAndRet(callFromBridge(context.llvm.Kotlin_ObjCExport_convertUnitToRetained, listOf(codegen.theUnitInstanceRef.llvm)))
        }

private fun ObjCExportCodeGenerator.createObjectInstanceAdapter(
        irClass: IrClass,
        selector: String
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    assert(irClass.kind == ClassKind.OBJECT)
    assert(!irClass.isUnit())

    return generateObjCToKotlinSyntheticGetter(selector) {
        initRuntimeIfNeeded() // For instance methods it gets called when allocating.
        val value = getObjectValue(irClass, startLocationInfo = null, exceptionHandler = ExceptionHandler.Caller)
        autoreleaseAndRet(kotlinReferenceToRetainedObjC(value))
    }
}

private fun ObjCExportCodeGenerator.createEnumEntryAdapter(
        irEnumEntry: IrEnumEntry,
        selector: String
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    return generateObjCToKotlinSyntheticGetter(selector) {
        initRuntimeIfNeeded() // For instance methods it gets called when allocating.

        val value = getEnumEntry(irEnumEntry, ExceptionHandler.Caller)
        autoreleaseAndRet(kotlinReferenceToRetainedObjC(value))
    }
}

private fun ObjCExportCodeGenerator.createEnumValuesAdapter(
        valuesFunction: IrFunction,
        selector: String
): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    val methodBridge = MethodBridge(
            returnBridge = MethodBridge.ReturnValue.Mapped(ReferenceBridge),
            receiver = MethodBridgeReceiver.Static,
            valueParameters = emptyList()
    )

    val imp = generateObjCImp(valuesFunction, valuesFunction, methodBridge, isVirtual = false)

    return objCToKotlinMethodAdapter(selector, methodBridge, imp)
}

private fun ObjCExportCodeGenerator.createThrowableAsErrorAdapter(): ObjCExportCodeGenerator.ObjCToKotlinMethodAdapter {
    val methodBridge = MethodBridge(
            returnBridge = MethodBridge.ReturnValue.Mapped(ReferenceBridge),
            receiver = MethodBridgeReceiver.Instance,
            valueParameters = emptyList()
    )

    val imp = generateObjCImpBy(methodBridge) {
        val exception = objCReferenceToKotlin(param(0), Lifetime.ARGUMENT)
        ret(callFromBridge(context.llvm.Kotlin_ObjCExport_WrapExceptionToNSError, listOf(exception)))
    }

    val selector = ObjCExportNamer.kotlinThrowableAsErrorMethodName
    return objCToKotlinMethodAdapter(selector, methodBridge, imp)
}

private fun objCFunctionType(context: Context, methodBridge: MethodBridge): LlvmFunctionSignature {
    val paramTypes = methodBridge.paramBridges.map { it.toLlvmParamType() }
    val returnType = methodBridge.returnBridge.toLlvmRetType(context)
    return LlvmFunctionSignature(returnType, paramTypes, isVararg = false)
}

private val ObjCValueType.llvmType: LLVMTypeRef get() = when (this) {
    ObjCValueType.BOOL -> int8Type
    ObjCValueType.UNICHAR -> int16Type
    ObjCValueType.CHAR -> int8Type
    ObjCValueType.SHORT -> int16Type
    ObjCValueType.INT -> int32Type
    ObjCValueType.LONG_LONG -> int64Type
    ObjCValueType.UNSIGNED_CHAR -> int8Type
    ObjCValueType.UNSIGNED_SHORT -> int16Type
    ObjCValueType.UNSIGNED_INT -> int32Type
    ObjCValueType.UNSIGNED_LONG_LONG -> int64Type
    ObjCValueType.FLOAT -> floatType
    ObjCValueType.DOUBLE -> doubleType
    ObjCValueType.POINTER -> kInt8Ptr
}

private fun MethodBridgeParameter.toLlvmParamType(): LlvmParamType = when (this) {
    is MethodBridgeValueParameter.Mapped -> this.bridge.toLlvmParamType()
    is MethodBridgeReceiver -> ReferenceBridge.toLlvmParamType()
    MethodBridgeSelector -> LlvmParamType(int8TypePtr)
    MethodBridgeValueParameter.ErrorOutParameter -> LlvmParamType(pointerType(ReferenceBridge.toLlvmParamType().llvmType))
    MethodBridgeValueParameter.SuspendCompletion -> LlvmParamType(int8TypePtr)
}

private fun MethodBridge.ReturnValue.toLlvmRetType(
        context: Context
): LlvmRetType = when (this) {
    MethodBridge.ReturnValue.Suspend,
    MethodBridge.ReturnValue.Void -> LlvmRetType(voidType)
    MethodBridge.ReturnValue.HashCode -> LlvmRetType(if (context.is64BitNSInteger()) int64Type else int32Type)
    is MethodBridge.ReturnValue.Mapped -> this.bridge.toLlvmParamType()
    MethodBridge.ReturnValue.WithError.Success -> ValueTypeBridge(ObjCValueType.BOOL).toLlvmParamType()

    MethodBridge.ReturnValue.Instance.InitResult,
    MethodBridge.ReturnValue.Instance.FactoryResult -> ReferenceBridge.toLlvmParamType()
    is MethodBridge.ReturnValue.WithError.ZeroForError -> this.successBridge.toLlvmRetType(context)
}

private fun TypeBridge.toLlvmParamType(): LlvmParamType = when (this) {
    is ReferenceBridge, is BlockPointerBridge -> LlvmParamType(int8TypePtr)
    is ValueTypeBridge -> LlvmParamType(this.objCValueType.llvmType, this.objCValueType.defaultParameterAttributes)
}

internal fun ObjCExportCodeGenerator.getEncoding(methodBridge: MethodBridge): String {
    var paramOffset = 0

    val params = buildString {
        methodBridge.paramBridges.forEach {
            append(it.objCEncoding)
            append(paramOffset)
            paramOffset += LLVMStoreSizeOfType(runtime.targetData, it.toLlvmParamType().llvmType).toInt()
        }
    }

    val returnTypeEncoding = methodBridge.returnBridge.getObjCEncoding(context)

    val paramSize = paramOffset
    return "$returnTypeEncoding$paramSize$params"
}

private fun MethodBridge.ReturnValue.getObjCEncoding(context: Context): String = when (this) {
    MethodBridge.ReturnValue.Suspend,
    MethodBridge.ReturnValue.Void -> "v"
    MethodBridge.ReturnValue.HashCode -> if (context.is64BitNSInteger()) "Q" else "I"
    is MethodBridge.ReturnValue.Mapped -> this.bridge.objCEncoding
    MethodBridge.ReturnValue.WithError.Success -> ObjCValueType.BOOL.encoding

    MethodBridge.ReturnValue.Instance.InitResult,
    MethodBridge.ReturnValue.Instance.FactoryResult -> ReferenceBridge.objCEncoding
    is MethodBridge.ReturnValue.WithError.ZeroForError -> this.successBridge.getObjCEncoding(context)
}

private val MethodBridgeParameter.objCEncoding: String get() = when (this) {
    is MethodBridgeValueParameter.Mapped -> this.bridge.objCEncoding
    is MethodBridgeReceiver -> ReferenceBridge.objCEncoding
    MethodBridgeSelector -> ":"
    MethodBridgeValueParameter.ErrorOutParameter -> "^${ReferenceBridge.objCEncoding}"
    MethodBridgeValueParameter.SuspendCompletion -> "@"
}

private val TypeBridge.objCEncoding: String get() = when (this) {
    ReferenceBridge, is BlockPointerBridge -> "@"
    is ValueTypeBridge -> this.objCValueType.encoding
}

private fun Context.is64BitNSInteger(): Boolean {
    val configurables = this.config.platform.configurables
    require(configurables is AppleConfigurables) {
        "Target ${configurables.target} has no support for NSInteger type."
    }
    return llvm.nsIntegerTypeWidth == 64L
}
