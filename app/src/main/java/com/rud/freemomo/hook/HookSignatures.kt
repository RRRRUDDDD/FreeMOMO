package com.rud.freemomo.hook

import com.rud.freemomo.util.ThrowablePolicy
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** One side-effect-free signature boundary shared by cache validation and installation. */
object HookSignatures {
    fun isWord(target: MethodSignature): Boolean =
        target.className == HookTargets.WORD_LIMIT_CLASS &&
            isStaticMethod(target, emptyList(), HookTargets.INT)

    fun isDisplay(target: DisplayHookTargets): Boolean =
        target.twoArgumentMethod.className == target.detailedMethod.className &&
            isStaticMethod(
                target.twoArgumentMethod,
                listOf(HookTargets.TEXT_VIEW, HookTargets.INT),
                HookTargets.VOID
            ) && isStaticMethod(
                target.detailedMethod,
                listOf(HookTargets.TEXT_VIEW, HookTargets.INT, HookTargets.BOOLEAN, HookTargets.FLOAT),
                HookTargets.VOID
            )

    fun isPrivilege(target: PrivilegeHookTargets): Boolean =
        target.methods.distinct().size == 3 &&
            target.methods.all { it.className == HookTargets.PRIVILEGE_CLASS } &&
            target.singleArgumentMethods.all {
                isStaticMethod(it, listOf(HookTargets.PRIVILEGE_CODE), HookTargets.BOOLEAN)
            } && isStaticMethod(
                target.twoArgumentMethod,
                listOf(HookTargets.PRIVILEGE_CODE, HookTargets.BOOLEAN),
                HookTargets.BOOLEAN
            )

    fun isCloud(target: MethodSignature): Boolean =
        '.' !in target.className && '$' !in target.className &&
            isStaticMethod(target, emptyList(), HookTargets.INT)

    fun isStaticMethod(
        target: MethodSignature,
        parameters: List<String>,
        returnType: String
    ): Boolean = target.isStatic && target.parameterTypes == parameters &&
        target.returnType == returnType

    fun resolve(target: MethodSignature, classLoader: ClassLoader): Method? = try {
        val declaringClass = Class.forName(target.className, false, classLoader)
        val parameters = target.parameterTypes.map { resolveType(it, classLoader) }.toTypedArray()
        declaringClass.getDeclaredMethod(target.methodName, *parameters).takeIf { method ->
            signature(method) == target
        }
    } catch (error: Throwable) {
        ThrowablePolicy.rethrowIfFatal(error)
        null
    }

    fun resolveAll(signatures: List<MethodSignature>, classLoader: ClassLoader): List<Method>? {
        return signatures.map { resolve(it, classLoader) ?: return null }
    }

    /** Throws on an unreadable declaration so callers cannot mistake it for an empty class. */
    fun describe(className: String, classLoader: ClassLoader): ClassDescriptor {
        val declaringClass = Class.forName(className, false, classLoader)
        return ClassDescriptor(
            declaringClass.name,
            declaringClass.declaredMethods.asSequence()
                .filterNot { it.isSynthetic || it.isBridge }
                .map(::signature)
                .toList()
        )
    }

    fun signature(method: Method): MethodSignature = MethodSignature(
        method.declaringClass.name,
        method.name,
        method.parameterTypes.map { it.name },
        method.returnType.name,
        Modifier.isStatic(method.modifiers)
    )

    fun resolveType(typeName: String, classLoader: ClassLoader): Class<*> = when (typeName) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> Class.forName(typeName, false, classLoader)
    }
}
