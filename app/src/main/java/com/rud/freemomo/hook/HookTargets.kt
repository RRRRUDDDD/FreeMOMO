package com.rud.freemomo.hook

/** A complete Java method identity used by exact mappings, discovery, and cache validation. */
data class MethodSignature(
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val isStatic: Boolean
) {
    val displayName: String
        get() = "$className.$methodName(${parameterTypes.joinToString()}) : $returnType"
}

/** Reflection-free description of one loaded class. */
data class ClassDescriptor(
    val className: String,
    val methods: List<MethodSignature>
) {
    /** Dex entries and bridge methods can be repeated; discovery counts identities once. */
    val uniqueMethods: List<MethodSignature> = methods.distinct()
}

data class DisplayHookTargets(
    val twoArgumentMethod: MethodSignature,
    val detailedMethod: MethodSignature
) {
    val methods: List<MethodSignature> = listOf(twoArgumentMethod, detailedMethod)
}

data class PrivilegeHookTargets(
    val singleArgumentMethods: List<MethodSignature>,
    val twoArgumentMethod: MethodSignature
) {
    init {
        require(singleArgumentMethods.size == 2) {
            "Privilege targets require exactly two single-argument methods"
        }
    }

    val methods: List<MethodSignature> = singleArgumentMethods + twoArgumentMethod
}

/** Four independently installable capabilities. Display and privilege remain atomic groups. */
data class HookTargets(
    val wordLimit: MethodSignature? = null,
    val display: DisplayHookTargets? = null,
    val privilege: PrivilegeHookTargets? = null,
    val cloud: MethodSignature? = null
) {
    val presentCapabilityCount: Int
        get() = listOf(wordLimit, display, privilege, cloud).count { it != null }

    fun isEmpty(): Boolean = presentCapabilityCount == 0

    companion object {
        const val WORD_LIMIT_CLASS = "com.maimemo.android.momo.a"
        const val PRIVILEGE_CLASS = "com.maimemo.android.momo.user.level.a"
        const val PRIVILEGE_CODE =
            "com.maimemo.android.momo.user.level.PrivilegeCode"
        const val TEXT_VIEW = "android.widget.TextView"
        const val CONTEXT = "android.content.Context"
        const val MAP = "java.util.Map"
        const val INTEGER = "java.lang.Integer"
        const val VOID = "void"
        const val BOOLEAN = "boolean"
        const val INT = "int"
        const val FLOAT = "float"

        private val builtInTargets = mapOf(
            893 to exactTargets(
                wordMethod = "r",
                displayClass = "q28",
                privilegeSingleMethods = listOf("l", "n"),
                privilegeTwoArgumentMethod = "m",
                cloudClass = "g85"
            ),
            898 to exactTargets(
                wordMethod = "s",
                displayClass = "n48",
                privilegeSingleMethods = listOf("k", "m"),
                privilegeTwoArgumentMethod = "l",
                cloudClass = "y95"
            )
        )

        fun builtIn(versionCode: Int): HookTargets? = builtInTargets[versionCode]

        fun supportedVersionCodes(): Set<Int> = builtInTargets.keys

        private fun exactTargets(
            wordMethod: String,
            displayClass: String,
            privilegeSingleMethods: List<String>,
            privilegeTwoArgumentMethod: String,
            cloudClass: String
        ): HookTargets {
            val displayShort = method(
                displayClass,
                "g",
                listOf(TEXT_VIEW, INT),
                VOID
            )
            val displayDetailed = method(
                displayClass,
                "h",
                listOf(TEXT_VIEW, INT, BOOLEAN, FLOAT),
                VOID
            )
            val singlePrivileges = privilegeSingleMethods.map { name ->
                method(PRIVILEGE_CLASS, name, listOf(PRIVILEGE_CODE), BOOLEAN)
            }
            val twoArgumentPrivilege = method(
                PRIVILEGE_CLASS,
                privilegeTwoArgumentMethod,
                listOf(PRIVILEGE_CODE, BOOLEAN),
                BOOLEAN
            )
            return HookTargets(
                wordLimit = method(WORD_LIMIT_CLASS, wordMethod, emptyList(), INT),
                display = DisplayHookTargets(displayShort, displayDetailed),
                privilege = PrivilegeHookTargets(singlePrivileges, twoArgumentPrivilege),
                cloud = method(cloudClass, "c", emptyList(), INT)
            )
        }

        private fun method(
            className: String,
            methodName: String,
            parameterTypes: List<String>,
            returnType: String
        ) = MethodSignature(className, methodName, parameterTypes, returnType, isStatic = true)
    }
}
