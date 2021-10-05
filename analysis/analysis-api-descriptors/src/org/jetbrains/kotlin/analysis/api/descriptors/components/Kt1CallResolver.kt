/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.components

import org.jetbrains.kotlin.analysis.api.calls.*
import org.jetbrains.kotlin.analysis.api.components.KtCallResolver
import org.jetbrains.kotlin.analysis.api.descriptors.Kt1AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescFunctionSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescPropertyGetterSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescPropertySetterSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.Kt1DescValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.callableId
import org.jetbrains.kotlin.analysis.api.descriptors.symbols.descriptorBased.base.toKtCallableSymbol
import org.jetbrains.kotlin.analysis.api.diagnostics.KtNonBoundToPsiErrorDiagnostic
import org.jetbrains.kotlin.analysis.api.symbols.KtFunctionLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtVariableLikeSymbol
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtSubstitutor
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.references.readWriteAccessWithFullExpressionWithPossibleResolve
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findAssignment
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.kotlin.resolve.calls.results.ResolutionStatus
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.isAnnotationConstructor
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.util.OperatorNameConventions

internal class Kt1CallResolver(override val analysisSession: Kt1AnalysisSession) : KtCallResolver() {
    private companion object {
        private const val UNRESOLVED_CALL_MESSAGE = "Unresolved call"

        private val kotlinFunctionInvokeCallableIds = (0..23).flatMapTo(hashSetOf()) { arity ->
            listOf(
                CallableId(StandardNames.getFunctionClassId(arity), OperatorNameConventions.INVOKE),
                CallableId(StandardNames.getSuspendFunctionClassId(arity), OperatorNameConventions.INVOKE)
            )
        }
    }

    override val token: ValidityToken
        get() = analysisSession.token

    override fun resolveAccessorCall(call: KtSimpleNameExpression): KtCall? {
        val bindingContext = analysisSession.analyze(call, Kt1AnalysisSession.AnalysisMode.PARTIAL_WITH_DIAGNOSTICS)
        val resolvedCall = call.getResolvedCall(bindingContext) ?: return null
        val resultingDescriptor = resolvedCall.resultingDescriptor

        if (resultingDescriptor is PropertyDescriptor) {
            @Suppress("DEPRECATION")
            val access = call.readWriteAccessWithFullExpressionWithPossibleResolve(
                readWriteAccessWithFullExpressionByResolve = { null }
            ).first

            val setterValue = findAssignment(call)?.right
            val accessorSymbol = when (resultingDescriptor) {
                is SyntheticJavaPropertyDescriptor -> {
                    when {
                        access.isWrite -> resultingDescriptor.setMethod?.let { Kt1DescFunctionSymbol(it, analysisSession) }
                        access.isRead -> Kt1DescFunctionSymbol(resultingDescriptor.getMethod, analysisSession)
                        else -> null
                    }
                }
                else -> {
                    when {
                        access.isWrite -> resultingDescriptor.setter?.let { Kt1DescPropertySetterSymbol(it, analysisSession) }
                        access.isRead -> resultingDescriptor.getter?.let { Kt1DescPropertyGetterSymbol(it, analysisSession) }
                        else -> null
                    }
                }
            }

            if (accessorSymbol != null) {
                val target = when {
                    !access.isWrite || setterValue != null -> KtSuccessCallTarget(accessorSymbol, token)
                    else -> {
                        val diagnostic = KtNonBoundToPsiErrorDiagnostic(factoryName = null, "Setter value is missing", token)
                        KtErrorCallTarget(listOf(accessorSymbol), diagnostic, token)
                    }
                }

                val argumentMapping = LinkedHashMap<KtExpression, KtValueParameterSymbol>()
                if (access.isWrite && setterValue != null) {
                    val setterParameterSymbol = accessorSymbol.valueParameters.single()
                    argumentMapping[setterValue] = setterParameterSymbol
                }

                return KtFunctionCall(argumentMapping, target, KtSubstitutor.Empty(token), token)
            }
        }

        return null
    }

    override fun resolveCall(call: KtCallElement): KtCall? = withValidityAssertion {
        return resolveCall(call, isUsualCall = true)
    }

    override fun resolveCall(call: KtBinaryExpression): KtCall? = withValidityAssertion {
        return resolveCall(call, isUsualCall = false)
    }

    override fun resolveCall(call: KtUnaryExpression): KtCall? = withValidityAssertion {
        return resolveCall(call, isUsualCall = false)
    }

    override fun resolveCall(call: KtArrayAccessExpression): KtCall? {
        return resolveCall(call, isUsualCall = false)
    }

    private fun resolveCall(call: KtElement, isUsualCall: Boolean): KtCall? {
        val bindingContext = analysisSession.analyze(call, Kt1AnalysisSession.AnalysisMode.PARTIAL_WITH_DIAGNOSTICS)
        val resolvedCall = call.getResolvedCall(bindingContext) ?: return getUnresolvedCall(call)

        val argumentMapping = createArgumentMapping(resolvedCall)

        fun getTarget(targetSymbol: KtFunctionLikeSymbol): KtCallTarget {
            if (resolvedCall.status == ResolutionStatus.SUCCESS) {
                return KtSuccessCallTarget(targetSymbol, token)
            }

            val diagnostic = KtNonBoundToPsiErrorDiagnostic(factoryName = null, UNRESOLVED_CALL_MESSAGE, token)
            return KtErrorCallTarget(listOf(targetSymbol), diagnostic, token)
        }

        val targetDescriptor = resolvedCall.resultingDescriptor

        val callableSymbol = targetDescriptor.toKtCallableSymbol(analysisSession) as? KtFunctionLikeSymbol ?: return null

        if (resolvedCall is VariableAsFunctionResolvedCall) {
            val variableDescriptor = resolvedCall.variableCall.resultingDescriptor
            val variableSymbol = variableDescriptor.toKtCallableSymbol(analysisSession) as? KtVariableLikeSymbol ?: return null

            val substitutor = KtSubstitutor.Empty(token)
            return if (resolvedCall.functionCall.resultingDescriptor.callableId in kotlinFunctionInvokeCallableIds) {
                KtFunctionalTypeVariableCall(variableSymbol, argumentMapping, getTarget(callableSymbol), substitutor, token)
            } else {
                KtVariableWithInvokeFunctionCall(variableSymbol, argumentMapping, getTarget(callableSymbol), substitutor, token)
            }
        }

        if (call is KtConstructorDelegationCall) {
            return KtDelegatedConstructorCall(argumentMapping, getTarget(callableSymbol), call.kind, token)
        }

        if (isUsualCall) {
            if (targetDescriptor.isAnnotationConstructor()) {
                return KtAnnotationCall(argumentMapping, getTarget(callableSymbol), token)
            }
        }

        return KtFunctionCall(argumentMapping, getTarget(callableSymbol), KtSubstitutor.Empty(token), token)
    }

    private fun getUnresolvedCall(call: KtElement): KtCall? {
        return when (call) {
            is KtSuperTypeCallEntry -> {
                val diagnostic = KtNonBoundToPsiErrorDiagnostic(factoryName = null, UNRESOLVED_CALL_MESSAGE, token)
                val target = KtErrorCallTarget(emptyList(), diagnostic, token)
                KtDelegatedConstructorCall(LinkedHashMap(), target, KtDelegatedConstructorCallKind.SUPER_CALL, token)
            }
            is KtConstructorDelegationCall -> {
                val diagnostic = KtNonBoundToPsiErrorDiagnostic(factoryName = null, UNRESOLVED_CALL_MESSAGE, token)
                val target = KtErrorCallTarget(emptyList(), diagnostic, token)
                return KtDelegatedConstructorCall(LinkedHashMap(), target, call.kind, token)
            }
            else -> null
        }
    }

    private val KtConstructorDelegationCall.kind: KtDelegatedConstructorCallKind
        get() = when {
            isCallToThis -> KtDelegatedConstructorCallKind.THIS_CALL
            else -> KtDelegatedConstructorCallKind.SUPER_CALL
        }

    private fun createArgumentMapping(resolvedCall: ResolvedCall<*>): LinkedHashMap<KtExpression, KtValueParameterSymbol> {
        val result = LinkedHashMap<KtExpression, KtValueParameterSymbol>()
        for ((parameter, arguments) in resolvedCall.valueArguments) {
            val parameterSymbol = Kt1DescValueParameterSymbol(parameter, analysisSession)

            for (argument in arguments.arguments) {
                val expression = argument.getArgumentExpression() ?: continue
                result[expression] = parameterSymbol
            }
        }
        return result
    }
}