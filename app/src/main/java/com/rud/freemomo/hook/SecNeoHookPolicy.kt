package com.rud.freemomo.hook

/** 360 加固壳早期 Hook 的最小策略，保持与 Xposed 运行时解耦以便单元测试。 */
internal object SecNeoHookPolicy {

    const val HELPER_CLASS = "com.secneo.apkwrapper.H"

    private const val NORMAL_PATH_GATE = "is"
    private const val NORMAL_PATH_ARGUMENT = 0

    val protectionWrapperMethods = setOf("callSW", "callBS")

    fun shouldForceNormalPath(methodName: String, argument: Int?): Boolean =
        methodName == NORMAL_PATH_GATE && argument == NORMAL_PATH_ARGUMENT

    fun shouldSuppressProtectionWrapper(methodName: String): Boolean =
        methodName in protectionWrapperMethods
}
