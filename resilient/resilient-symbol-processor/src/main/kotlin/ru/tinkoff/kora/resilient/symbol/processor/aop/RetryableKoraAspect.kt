package ru.tinkoff.kora.resilient.symbol.processor.aop

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.tinkoff.kora.aop.symbol.processor.KoraAspect
import ru.tinkoff.kora.ksp.common.FunctionUtils.isFlow
import ru.tinkoff.kora.ksp.common.FunctionUtils.isFlux
import ru.tinkoff.kora.ksp.common.FunctionUtils.isFuture
import ru.tinkoff.kora.ksp.common.FunctionUtils.isMono
import ru.tinkoff.kora.ksp.common.FunctionUtils.isSuspend
import ru.tinkoff.kora.ksp.common.KotlinPoetUtils.controlFlow
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import java.util.concurrent.Future

@KspExperimental
class RetryableKoraAspect(val resolver: Resolver) : KoraAspect {

    companion object {
        const val ANNOTATION_TYPE: String = "ru.tinkoff.kora.resilient.retry.annotation.Retryable"
        private val MEMBER_RETRY_STATUS = ClassName("ru.tinkoff.kora.resilient.retry", "Retrier", "RetryState", "RetryStatus")
        private val MEMBER_RETRY_EXCEPTION = MemberName("ru.tinkoff.kora.resilient.retry", "RetryAttemptException")
        private val MEMBER_DELAY = MemberName("kotlinx.coroutines", "delay")
        private val MEMBER_TIME = MemberName("kotlin.time.Duration.Companion", "nanoseconds")
        private val MEMBER_FLOW = MemberName("kotlinx.coroutines.flow", "flow")
        private val MEMBER_FLOW_EMIT = MemberName("kotlinx.coroutines.flow", "emitAll")
        private val MEMBER_FLOW_RETRY = MemberName("kotlinx.coroutines.flow", "retryWhen")
    }

    override fun getSupportedAnnotationTypes(): Set<String> {
        return setOf(ANNOTATION_TYPE)
    }

    override fun apply(method: KSFunctionDeclaration, superCall: String, aspectContext: KoraAspect.AspectContext): KoraAspect.ApplyResult {
        if (method.isFuture()) {
            throw ProcessingErrorException("@Retryable can't be applied for types assignable from ${Future::class.java}", method)
        } else if (method.isMono()) {
            throw ProcessingErrorException("@Retryable can't be applied for types assignable from ${Mono::class.java}", method)
        } else if (method.isFlux()) {
            throw ProcessingErrorException("@Retryable can't be applied for types assignable from ${Flux::class.java}", method)
        }

        val annotation = method.annotations.filter { a -> a.annotationType.resolve().toClassName().canonicalName == ANNOTATION_TYPE }.first()
        val retryableName = annotation.arguments.asSequence().filter { arg -> arg.name!!.getShortName() == "value" }.map { arg -> arg.value.toString() }.first()

        val managerType = resolver.getClassDeclarationByName("ru.tinkoff.kora.resilient.retry.RetrierManager")!!.asType(listOf())
        val fieldManager = aspectContext.fieldFactory.constructorParam(managerType, listOf())
        val retrierType = resolver.getClassDeclarationByName("ru.tinkoff.kora.resilient.retry.Retrier")!!.asType(listOf())
        val fieldRetrier = aspectContext.fieldFactory.constructorInitialized(
            retrierType,
            CodeBlock.of("%L[%S]", fieldManager, retryableName)
        )

        val body = if (method.isFlow()) {
            buildBodyFlow(method, superCall, retryableName, fieldRetrier)
        } else if (method.isSuspend()) {
            buildBodySync(method, superCall, retryableName, fieldRetrier)
        } else {
            buildBodySync(method, superCall, retryableName, fieldRetrier)
        }

        return KoraAspect.ApplyResult.MethodBody(body)
    }

    private fun buildBodySync(method: KSFunctionDeclaration, superCall: String, retryName: String, fieldRetrier: String): CodeBlock {
        return CodeBlock.builder()
            .add("return %L.asState()", fieldRetrier).indent().add("\n")
            .controlFlow(".use { _state ->", fieldRetrier) {
                addStatement("var _cause: Exception? = null")
                addStatement("lateinit var _result: %T", method.returnType?.resolve()?.toTypeName())
                controlFlow("while (true)") {
                    controlFlow("try") {
                        add("_result = ").add(buildMethodCall(method, superCall)).add("\n")
                        addStatement("break")
                        nextControlFlow("catch (_e: Exception)")
                        addStatement("val _status = _state.onException(_e)")
                        controlFlow("when (_status)") {
                            addStatement("%T.REJECTED -> throw _e", MEMBER_RETRY_STATUS)
                            controlFlow("%T.ACCEPTED ->", MEMBER_RETRY_STATUS) {
                                add(
                                    """
                            if (_cause == null) {
                                _cause = _e
                            } else {
                                _cause.addSuppressed(_e)
                            }
                            
                            """.trimIndent()
                                )
                                if (method.isSuspend()) {
                                    addStatement("%M(_state.delayNanos.%M)", MEMBER_DELAY, MEMBER_TIME)
                                } else {
                                    addStatement("_state.doDelay()")
                                }
                            }
                            controlFlow("%T.EXHAUSTED ->", MEMBER_RETRY_STATUS) {
                                add(
                                    """
                            var _exhaustedException = %M(_state.getAttempts())
                            if (_cause != null) {
                                _exhaustedException.addSuppressed(_cause)
                            }
                            throw _exhaustedException
                            
                            """.trimIndent(), MEMBER_RETRY_EXCEPTION
                                )
                            }
                        }
                    }
                }
                addStatement("return@use _result")
            }
            .unindent()
            .add("\n")
            .build()
    }

    private fun buildBodyFlow(method: KSFunctionDeclaration, superCall: String, retryName: String, fieldRetrier: String): CodeBlock {
        return CodeBlock.builder().controlFlow("return %M", MEMBER_FLOW) {
            controlFlow("return@flow %L.asState().use { _state ->", fieldRetrier) {
                add("%M (", MEMBER_FLOW_EMIT).indent()
                add(buildMethodCall(method, superCall)).add(".").controlFlow("%M { _cause, _ ->", MEMBER_FLOW_RETRY) {
                    addStatement("val _status = _state.onException(_cause)")
                    controlFlow("when (_status)") {
                        addStatement("%T.REJECTED -> false", MEMBER_RETRY_STATUS)
                        controlFlow("%T.ACCEPTED ->", MEMBER_RETRY_STATUS) {
                            addStatement("%M(_state.delayNanos.%M)", MEMBER_DELAY, MEMBER_TIME)
                            addStatement("true")
                        }
                        controlFlow("%T.EXHAUSTED ->", MEMBER_RETRY_STATUS) {
                            addStatement("throw %M(_state.getAttempts())", MEMBER_RETRY_EXCEPTION)
                        }
                    }
                }
                unindent().add("\n").add(")\n")
            }
        }.build()
    }

    private fun buildMethodCall(method: KSFunctionDeclaration, call: String): CodeBlock {
        return CodeBlock.of(method.parameters.asSequence().map { p -> CodeBlock.of("%L", p) }.joinToString(", ", "$call(", ")"))
    }
}
