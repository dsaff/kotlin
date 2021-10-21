/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.llvm.LlvmParameterAttribute
import org.jetbrains.kotlin.builtins.UnsignedType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId

// TODO: We have [org.jetbrains.kotlin.native.interop.gen.ObjCAbiInfo] which does similar thing.
//  Consider unifying these classes.
//  Also there are ABI-specific pieces in codegen and ObjCExport. It makes sense to move them here as well.
/**
 * Encapsulates ABI-specific logic for code generation.
 *
 * Similar Clang classes:
 *  * [ABIInfo](https://github.com/llvm/llvm-project/blob/2a36f29fce91b6242ebd926d7c08381c31138d2c/clang/lib/CodeGen/ABIInfo.h#L50)
 *  * [TargetCodeGenInfo](https://github.com/llvm/llvm-project/blob/2831a317b689c7f005a29f008a8e4c24485c0711/clang/lib/CodeGen/TargetInfo.h#L45)
 */
sealed interface TargetAbiInfo {
    fun defaultParameterAttributesForIrType(irType: IrType): List<LlvmParameterAttribute>
}

object ExtendOnCallerSideTargetAbiInfo : TargetAbiInfo {
    override fun defaultParameterAttributesForIrType(irType: IrType): List<LlvmParameterAttribute> {
        // TODO: We perform type unwrapping twice: one to get the underlying type, and then this one.
        //  Unwrapping is not cheap, so it might affect compilation time.
        return irType.unwrapToPrimitiveOrReference(
                eachInlinedClass = { inlinedClass, _ ->
                    when (inlinedClass.classId) {
                        UnsignedType.UBYTE.classId -> return listOf(LlvmParameterAttribute.ZeroExt)
                        UnsignedType.USHORT.classId -> return listOf(LlvmParameterAttribute.ZeroExt)
                    }
                },
                ifPrimitive = { primitiveType, _ ->
                    when (primitiveType) {
                        KonanPrimitiveType.BOOLEAN -> listOf(LlvmParameterAttribute.ZeroExt)
                        KonanPrimitiveType.CHAR -> listOf(LlvmParameterAttribute.ZeroExt)
                        KonanPrimitiveType.BYTE -> listOf(LlvmParameterAttribute.SignExt)
                        KonanPrimitiveType.SHORT -> listOf(LlvmParameterAttribute.SignExt)
                        KonanPrimitiveType.INT -> emptyList()
                        KonanPrimitiveType.LONG -> emptyList()
                        KonanPrimitiveType.FLOAT -> emptyList()
                        KonanPrimitiveType.DOUBLE -> emptyList()
                        KonanPrimitiveType.NON_NULL_NATIVE_PTR -> emptyList()
                        KonanPrimitiveType.VECTOR128 -> emptyList()
                    }
                },
                ifReference = {
                    return emptyList()
                },
        )
    }
}

sealed class ExtendOnCalleeSideTargetAbiInfo(private val shouldZeroExtBoolean: Boolean) : TargetAbiInfo {
    override fun defaultParameterAttributesForIrType(irType: IrType): List<LlvmParameterAttribute> {
        return irType.unwrapToPrimitiveOrReference(
                eachInlinedClass = { _, _ -> },
                ifPrimitive = { primitiveType, _ ->
                    when (primitiveType) {
                        KonanPrimitiveType.BOOLEAN -> if (shouldZeroExtBoolean) {
                            listOf(LlvmParameterAttribute.ZeroExt)
                        } else {
                            emptyList()
                        }
                        KonanPrimitiveType.CHAR -> emptyList()
                        KonanPrimitiveType.BYTE -> emptyList()
                        KonanPrimitiveType.SHORT -> emptyList()
                        KonanPrimitiveType.INT -> emptyList()
                        KonanPrimitiveType.LONG -> emptyList()
                        KonanPrimitiveType.FLOAT -> emptyList()
                        KonanPrimitiveType.DOUBLE -> emptyList()
                        KonanPrimitiveType.NON_NULL_NATIVE_PTR -> emptyList()
                        KonanPrimitiveType.VECTOR128 -> emptyList()
                    }
                },
                ifReference = {
                    return emptyList()
                },
        )
    }
}

/**
 * Procedure Call Standard for the Arm 64-bit Architecture.
 * http://infocenter.arm.com/help/topic/com.arm.doc.ihi0055a/IHI0055A_aapcs64.pdf
 *
 * Note that Apple uses different its own variant called DarwinPCS.
 */
object AAPCS64TargetAbiInfo : ExtendOnCalleeSideTargetAbiInfo(shouldZeroExtBoolean = false)

/**
 * Windows x64 ABI.
 * https://docs.microsoft.com/en-us/cpp/build/x64-calling-convention
 */
object WindowsX64TargetAbiInfo : ExtendOnCalleeSideTargetAbiInfo(shouldZeroExtBoolean = true)

/**
 * "Generic" ABI info that is applicable for the most of our current targets.
 * In time will be replaced by more specific [TargetAbiInfo] inheritors.
 */
val DefaultTargetAbiInfo: TargetAbiInfo = ExtendOnCallerSideTargetAbiInfo