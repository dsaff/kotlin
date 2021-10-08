/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base

import org.jetbrains.kotlin.analysis.api.KtStarProjectionTypeArgument
import org.jetbrains.kotlin.analysis.api.KtTypeArgument
import org.jetbrains.kotlin.analysis.api.KtTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.components.KtDeclarationRendererOptions
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.*
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.psiBased.base.Kt1PsiSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.types.*
import org.jetbrains.kotlin.analysis.api.descriptors.types.base.Kt1TypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.descriptors.utils.Kt1Renderer
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.*
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.SyntheticFieldDescriptor
import org.jetbrains.kotlin.descriptors.impl.TypeAliasConstructorDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaCallableMemberDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaForKotlinOverridePropertyDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaPropertyDescriptor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.inference.CapturedType
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.resolve.descriptorUtil.annotationClass
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.sam.SamConstructorDescriptor
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.NewCapturedType
import org.jetbrains.kotlin.types.checker.NewTypeVariableConstructor

internal val MemberDescriptor.ktSymbolKind: KtSymbolKind
    get() = when {
        containingDeclaration is PackageFragmentDescriptor -> KtSymbolKind.TOP_LEVEL
        DescriptorUtils.isLocal(this) -> KtSymbolKind.LOCAL
        else -> KtSymbolKind.CLASS_MEMBER
    }

internal val CallableMemberDescriptor.isExplicitOverride: Boolean
    get() {
        return (this !is PropertyAccessorDescriptor
                && kind != CallableMemberDescriptor.Kind.FAKE_OVERRIDE
                && overriddenDescriptors.isNotEmpty())
    }

internal val ClassDescriptor.isInterfaceLike: Boolean
    get() = when (kind) {
        ClassKind.CLASS, ClassKind.ENUM_CLASS, ClassKind.OBJECT, ClassKind.ENUM_ENTRY -> false
        else -> true
    }

internal fun DeclarationDescriptor.toKtSymbol(analysisSession: Kt1AnalysisSession): KtSymbol? {
    if (this is ClassDescriptor && kind == ClassKind.ENUM_ENTRY) {
        return Kt1DescEnumEntrySymbol(this, analysisSession)
    }

    return when (this) {
        is ClassifierDescriptor -> toKtClassifierSymbol(analysisSession)
        is CallableDescriptor -> toKtCallableSymbol(analysisSession)
        else -> null
    }
}

internal fun ClassifierDescriptor.toKtClassifierSymbol(analysisSession: Kt1AnalysisSession): KtClassifierSymbol? {
    return when (this) {
        is TypeAliasDescriptor -> Kt1DescTypeAliasSymbol(this, analysisSession)
        is TypeParameterDescriptor -> Kt1DescTypeParameterSymbol(this, analysisSession)
        is ClassDescriptor -> toKtClassSymbol(analysisSession)
        else -> null
    }
}

internal fun ClassDescriptor.toKtClassSymbol(analysisSession: Kt1AnalysisSession): KtClassOrObjectSymbol? {
    return if (DescriptorUtils.isAnonymousObject(this)) {
        Kt1DescAnonymousObjectSymbol(this, analysisSession)
    } else {
        Kt1DescNamedClassOrObjectSymbol(this, analysisSession)
    }
}

internal fun ConstructorDescriptor.toKtConstructorSymbol(analysisSession: Kt1AnalysisSession): KtConstructorSymbol {
    if (this is TypeAliasConstructorDescriptor) {
        return this.underlyingConstructorDescriptor.toKtConstructorSymbol(analysisSession)
    }

    return Kt1DescConstructorSymbol(this, analysisSession)
}

internal val CallableMemberDescriptor.ktHasStableParameterNames: Boolean
    get() = when {
        this is ConstructorDescriptor && isPrimary && constructedClass.kind == ClassKind.ANNOTATION_CLASS -> true
        isExpect -> false
        else -> when (this) {
            is JavaCallableMemberDescriptor -> false
            else -> hasStableParameterNames()
        }
    }

internal fun CallableDescriptor.toKtCallableSymbol(analysisSession: Kt1AnalysisSession): KtCallableSymbol? {
    return when (this) {
        is PropertyGetterDescriptor -> Kt1DescPropertyGetterSymbol(this, analysisSession)
        is PropertySetterDescriptor -> Kt1DescPropertySetterSymbol(this, analysisSession)
        is SamConstructorDescriptor -> Kt1DescSamConstructorSymbol(this, analysisSession)
        is ConstructorDescriptor -> toKtConstructorSymbol(analysisSession)
        is FunctionDescriptor -> {
            if (DescriptorUtils.isAnonymousFunction(this)) {
                Kt1DescAnonymousFunctionSymbol(this, analysisSession)
            } else {
                Kt1DescFunctionSymbol(this, analysisSession)
            }
        }
        is SyntheticFieldDescriptor -> Kt1DescSyntheticFieldSymbol(this, analysisSession)
        is LocalVariableDescriptor -> Kt1DescLocalVariableSymbol(this, analysisSession)
        is ValueParameterDescriptor -> Kt1DescValueParameterSymbol(this, analysisSession)
        is SyntheticJavaPropertyDescriptor -> Kt1DescSyntheticJavaPropertySymbol(this, analysisSession)
        is JavaForKotlinOverridePropertyDescriptor -> Kt1DescSyntheticJavaPropertySymbolForOverride(this, analysisSession)
        is JavaPropertyDescriptor -> Kt1DescJavaFieldSymbol(this, analysisSession)
        is PropertyDescriptorImpl -> Kt1DescKotlinPropertySymbol(this, analysisSession)
        else -> null
    }
}

internal fun KotlinType.toKtType(analysisSession: Kt1AnalysisSession): KtType {
    return when (val unwrappedType = unwrap()) {
        is FlexibleType -> Kt1FlexibleType(unwrappedType, analysisSession)
        is DefinitelyNotNullType -> Kt1DefinitelyNotNullType(unwrappedType, analysisSession)
        is ErrorType -> Kt1ClassErrorType(unwrappedType, analysisSession)
        is CapturedType -> Kt1CapturedType(unwrappedType, analysisSession)
        is NewCapturedType -> Kt1NewCapturedType(unwrappedType, analysisSession)
        is SimpleType -> {
            val typeParameterDescriptor = TypeUtils.getTypeParameterDescriptorOrNull(unwrappedType)
            if (typeParameterDescriptor != null) {
                return Kt1TypeParameterType(unwrappedType, typeParameterDescriptor, analysisSession)
            }

            val typeConstructor = unwrappedType.constructor

            if (typeConstructor is NewTypeVariableConstructor) {
                val newTypeParameterDescriptor = typeConstructor.originalTypeParameter
                return if (newTypeParameterDescriptor != null) {
                    Kt1TypeParameterType(unwrappedType, newTypeParameterDescriptor, analysisSession)
                } else {
                    Kt1ClassErrorType(ErrorUtils.createErrorType("Unresolved type parameter type") as ErrorType, analysisSession)
                }
            }

            if (typeConstructor is IntersectionTypeConstructor) {
                return Kt1IntersectionType(unwrappedType, typeConstructor.supertypes, analysisSession)
            }

            return when (val typeDeclaration = typeConstructor.declarationDescriptor) {
                is FunctionClassDescriptor -> Kt1FunctionalType(unwrappedType, typeDeclaration, analysisSession)
                is ClassDescriptor -> Kt1UsualClassType(unwrappedType, typeDeclaration, analysisSession)
                else -> {
                    val errorType = ErrorUtils.createErrorTypeWithCustomConstructor("Unresolved class type", typeConstructor)
                    Kt1ClassErrorType(errorType as ErrorType, analysisSession)
                }
            }

        }
        else -> error("Unexpected type $this")
    }
}

internal fun KotlinType.toKtTypeAndAnnotations(analysisSession: Kt1AnalysisSession): KtTypeAndAnnotations {
    return Kt1TypeAndAnnotations(toKtType(analysisSession), this, analysisSession.token)
}

internal fun TypeProjection.toKtTypeArgument(analysisSession: Kt1AnalysisSession): KtTypeArgument {
    return if (isStarProjection) {
        KtStarProjectionTypeArgument(analysisSession.token)
    } else {
        KtTypeArgumentWithVariance(type.toKtType(analysisSession), this.projectionKind, analysisSession.token)
    }
}

internal val KotlinType.ktNullability: KtTypeNullability
    get() = when {
        this.isNullabilityFlexible() -> KtTypeNullability.UNKNOWN
        this.isMarkedNullable -> KtTypeNullability.NULLABLE
        else -> KtTypeNullability.NON_NULLABLE
    }

internal val DeclarationDescriptorWithVisibility.ktVisibility: Visibility
    get() = when (visibility) {
        DescriptorVisibilities.PUBLIC -> Visibilities.Public
        DescriptorVisibilities.PROTECTED -> Visibilities.Protected
        DescriptorVisibilities.INTERNAL -> Visibilities.Internal
        DescriptorVisibilities.PRIVATE -> Visibilities.Private
        DescriptorVisibilities.PRIVATE_TO_THIS -> Visibilities.PrivateToThis
        DescriptorVisibilities.LOCAL -> Visibilities.Local
        DescriptorVisibilities.INVISIBLE_FAKE -> Visibilities.InvisibleFake
        DescriptorVisibilities.INHERITED -> Visibilities.Inherited
        else -> Visibilities.Unknown
    }

internal fun ConstantValue<*>.toKtConstantValue(): KtConstantValue {
    return when (this) {
        is BooleanValue -> KtLiteralConstantValue(ConstantValueKind.Boolean, value, null)
        is CharValue -> KtLiteralConstantValue(ConstantValueKind.Char, value, null)
        is ByteValue -> KtLiteralConstantValue(ConstantValueKind.Byte, value, null)
        is UByteValue -> KtLiteralConstantValue(ConstantValueKind.UnsignedByte, value, null)
        is ShortValue -> KtLiteralConstantValue(ConstantValueKind.Short, value, null)
        is UShortValue -> KtLiteralConstantValue(ConstantValueKind.UnsignedShort, value, null)
        is IntValue -> KtLiteralConstantValue(ConstantValueKind.Int, value, null)
        is UIntValue -> KtLiteralConstantValue(ConstantValueKind.UnsignedInt, value, null)
        is LongValue -> KtLiteralConstantValue(ConstantValueKind.Long, value, null)
        is ULongValue -> KtLiteralConstantValue(ConstantValueKind.UnsignedLong, value, null)
        is FloatValue -> KtLiteralConstantValue(ConstantValueKind.Float, value, null)
        is DoubleValue -> KtLiteralConstantValue(ConstantValueKind.Double, value, null)
        is NullValue -> KtLiteralConstantValue(ConstantValueKind.Null, null, null)
        is StringValue -> KtLiteralConstantValue(ConstantValueKind.String, value, null)
        is ArrayValue -> KtArrayConstantValue(value.map { it.toKtConstantValue() }, null)
        is EnumValue -> KtEnumEntryConstantValue(CallableId(enumClassId, enumEntryName), null)
        is AnnotationValue -> {
            val arguments = value.allValueArguments.map { (name, v) -> KtNamedConstantValue(name.asString(), v.toKtConstantValue()) }
            KtAnnotationConstantValue(value.annotationClass?.classId, arguments, null)
        }
        else -> KtUnsupportedConstantValue
    }
}

internal val CallableMemberDescriptor.callableId: CallableId?
    get() {
        var current: DeclarationDescriptor = containingDeclaration

        val localName = mutableListOf<String>()
        val className = mutableListOf<String>()

        while (true) {
            when (current) {
                is PackageFragmentDescriptor -> {
                    return CallableId(
                        packageName = current.fqName,
                        className = if (className.isNotEmpty()) FqName.fromSegments(className.asReversed()) else null,
                        callableName = name,
                        pathToLocal = if (localName.isNotEmpty()) FqName.fromSegments(localName.asReversed()) else null
                    )
                }
                is ClassDescriptor -> className += current.name.asString()
                is PropertyAccessorDescriptor -> {} // Filter out property accessors
                is CallableDescriptor -> localName += current.name.asString()
            }

            current = current.containingDeclaration ?: return null
        }
    }

internal fun getSymbolDescriptor(symbol: KtSymbol): DeclarationDescriptor? {
    return when (symbol) {
        is Kt1DescSymbol<*> -> symbol.descriptor
        is Kt1PsiSymbol<*, *> -> symbol.descriptor
        else -> null
    }
}

internal val ClassifierDescriptor.classId: ClassId?
    get() = when (val owner = containingDeclaration) {
        is PackageFragmentDescriptor -> ClassId(owner.fqName, name)
        is ClassifierDescriptorWithTypeParameters -> owner.classId?.createNestedClassId(name)
        else -> null
    }

internal val ClassifierDescriptor.maybeLocalClassId: ClassId
    get() = classId ?: ClassId(containingPackage() ?: FqName.ROOT, FqName.topLevel(this.name), true)

internal fun ClassDescriptor.getSupertypesWithAny(): Collection<KotlinType> {
    val supertypes = typeConstructor.supertypes
    if (isInterfaceLike) {
        return supertypes
    }

    val hasClassSupertype = supertypes.any { (it.constructor.declarationDescriptor as? ClassDescriptor)?.kind == ClassKind.CLASS }
    return if (hasClassSupertype) supertypes else listOf(builtIns.anyType) + supertypes
}

internal fun DeclarationDescriptor.render(analysisSession: Kt1AnalysisSession, options: KtDeclarationRendererOptions): String {
    val renderer = Kt1Renderer(analysisSession, options)
    val consumer = StringBuilder()
    renderer.render(this, consumer)
    return consumer.toString().trim(' ', '\n', '\t')
}

internal fun CallableMemberDescriptor.getSymbolPointerSignature(analysisSession: Kt1AnalysisSession): String {
    return render(analysisSession, KtDeclarationRendererOptions.DEFAULT)
}