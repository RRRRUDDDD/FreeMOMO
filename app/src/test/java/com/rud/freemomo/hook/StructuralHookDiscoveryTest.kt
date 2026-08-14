package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralHookDiscoveryTest {

    @Test
    fun `893 and 898 shadow discovery exactly reproduce built in targets`() {
        listOf(893, 898).forEach { versionCode ->
            val fixture = fixtureFor(versionCode)

            val result = StructuralHookDiscovery.discover(
                fixture.classes,
                fixture.wordLimitResults
            )

            assertEquals(DiscoveryStatus.DISCOVERED, result.wordLimit.status)
            assertEquals(DiscoveryStatus.DISCOVERED, result.display.status)
            assertEquals(DiscoveryStatus.DISCOVERED, result.privilege.status)
            assertEquals(DiscoveryStatus.DISCOVERED, result.cloud.status)
            assertEquals(HookTargets.builtIn(versionCode), result.targets)
        }
    }

    @Test
    fun `word discovery accepts only exact fixed class static int no arg methods`() {
        val exact = method(WORD_CLASS, "candidate", returnType = INT)
        val state = StructuralHookDiscovery.discoverWordLimit(
            listOf(
                descriptor(
                    WORD_CLASS,
                    exact,
                    exact,
                    method(WORD_CLASS, "boxed", returnType = INTEGER),
                    method(WORD_CLASS, "argument", listOf(BOOLEAN), INT),
                    method(WORD_CLASS, "instance", returnType = INT, isStatic = false)
                ),
                descriptor("com.maimemo.android.momo.b", method(
                    "com.maimemo.android.momo.b",
                    "other",
                    returnType = INT
                ))
            )
        )

        assertEquals(DiscoveryStatus.PENDING, state.status)
        assertEquals(listOf(exact), state.candidates)
        assertEquals(DiscoveryStatus.REJECTED, state.observe(exact, 600).status)
        assertEquals(DiscoveryStatus.REJECTED, state.observe(exact, 20_000).status)
        assertEquals(exact, state.observe(exact, 601).target)
        assertEquals(exact, state.observe(exact, 19_999).target)
    }

    @Test
    fun `word discovery becomes ambiguous when a late second candidate matches`() {
        val first = method(WORD_CLASS, "first", returnType = INT)
        val second = method(WORD_CLASS, "second", returnType = INT)
        var state = StructuralHookDiscovery.discoverWordLimit(
            listOf(descriptor(WORD_CLASS, first, second))
        )

        state = state.observe(first, 669)
        assertEquals(DiscoveryStatus.DISCOVERED, state.status)
        assertEquals(first, state.target)

        state = state.observe(second, 42)
        assertEquals(DiscoveryStatus.DISCOVERED, state.status)
        assertEquals(first, state.target)

        state = state.observe(second, 670)
        assertEquals(DiscoveryStatus.AMBIGUOUS, state.status)
        assertNull(state.target)
        assertEquals(listOf(first, second), state.conflicts)

        state = state.observe(first, 669)
        assertEquals(DiscoveryStatus.AMBIGUOUS, state.status)
        assertEquals(listOf(first, second), state.conflicts)
    }

    @Test
    fun `word discovery distinguishes missing pending and rejected`() {
        assertEquals(
            DiscoveryStatus.MISSING,
            StructuralHookDiscovery.discoverWordLimit(emptyList()).status
        )

        val near = method(WORD_CLASS, "near", returnType = INTEGER)
        val rejected = StructuralHookDiscovery.discoverWordLimit(
            listOf(descriptor(WORD_CLASS, near))
        )
        assertEquals(DiscoveryStatus.REJECTED, rejected.status)
        assertEquals(listOf(near), rejected.rejectedCandidates)

        val exact = method(WORD_CLASS, "exact", returnType = INT)
        assertEquals(
            DiscoveryStatus.PENDING,
            StructuralHookDiscovery.discoverWordLimit(
                listOf(descriptor(WORD_CLASS, exact))
            ).status
        )
    }

    @Test
    fun `display discovery ignores method names but requires one exact pair`() {
        val short = method("obfuscated", "alpha", DISPLAY_SHORT, VOID)
        val detailed = method("obfuscated", "omega", DISPLAY_DETAILED, VOID)

        val result = StructuralHookDiscovery.discoverDisplay(
            listOf(
                descriptor("obfuscated", short, detailed, short, detailed),
                descriptor(
                    "partial",
                    method("partial", "near", listOf(TEXT_VIEW, LONG), VOID)
                )
            )
        )

        assertEquals(DiscoveryStatus.DISCOVERED, result.status)
        assertEquals(DisplayHookTargets(short, detailed), result.target)
    }

    @Test
    fun `display discovery rejects near signatures and reports multiple exact classes`() {
        val wrongReturn = method("near", "a", DISPLAY_SHORT, BOOLEAN)
        val wrongParameter = method(
            "near",
            "b",
            listOf(TEXT_VIEW, LONG, BOOLEAN, FLOAT),
            VOID
        )
        val instance = method("near", "c", DISPLAY_DETAILED, VOID, isStatic = false)
        val rejected = StructuralHookDiscovery.discoverDisplay(
            listOf(descriptor("near", wrongReturn, wrongParameter, instance))
        )
        assertEquals(DiscoveryStatus.REJECTED, rejected.status)
        assertTrue(rejected.rejectedCandidates.containsAll(
            listOf(wrongReturn, wrongParameter, instance)
        ))

        val one = displayDescriptor("one", "x", "y")
        val two = displayDescriptor("two", "m", "n")
        val ambiguous = StructuralHookDiscovery.discoverDisplay(listOf(one, two))
        assertEquals(DiscoveryStatus.AMBIGUOUS, ambiguous.status)
        assertEquals(4, ambiguous.conflicts.size)
        assertNull(ambiguous.target)
    }

    @Test
    fun `privilege discovery requires exact fixed class two plus one structure`() {
        val singleOne = method(PRIVILEGE_CLASS, "anything", listOf(PRIVILEGE_CODE), BOOLEAN)
        val double = method(
            PRIVILEGE_CLASS,
            "middle",
            listOf(PRIVILEGE_CODE, BOOLEAN),
            BOOLEAN
        )
        val singleTwo = method(PRIVILEGE_CLASS, "last", listOf(PRIVILEGE_CODE), BOOLEAN)
        val result = StructuralHookDiscovery.discoverPrivilege(
            listOf(descriptor(
                PRIVILEGE_CLASS,
                singleOne,
                double,
                singleTwo,
                singleOne
            ))
        )

        assertEquals(DiscoveryStatus.DISCOVERED, result.status)
        assertEquals(
            PrivilegeHookTargets(listOf(singleOne, singleTwo), double),
            result.target
        )
    }

    @Test
    fun `privilege discovery rejects extra matching and near methods`() {
        val exact = privilegeDescriptor(893)
        val extra = method(PRIVILEGE_CLASS, "z", listOf(PRIVILEGE_CODE), BOOLEAN)
        val extraResult = StructuralHookDiscovery.discoverPrivilege(
            listOf(exact.copy(methods = exact.methods + extra))
        )
        assertEquals(DiscoveryStatus.REJECTED, extraResult.status)
        assertTrue(extraResult.rejectedCandidates.contains(extra))

        val nearResult = StructuralHookDiscovery.discoverPrivilege(
            listOf(descriptor(
                PRIVILEGE_CLASS,
                method(PRIVILEGE_CLASS, "a", listOf(PRIVILEGE_CODE), BOOLEAN,
                    isStatic = false),
                method(PRIVILEGE_CLASS, "b", listOf(PRIVILEGE_CODE), "java.lang.Boolean"),
                method(PRIVILEGE_CLASS, "c", listOf(PRIVILEGE_CODE, INT), BOOLEAN)
            ))
        )
        assertEquals(DiscoveryStatus.REJECTED, nearResult.status)
        assertNull(nearResult.target)

        assertEquals(
            DiscoveryStatus.MISSING,
            StructuralHookDiscovery.discoverPrivilege(emptyList()).status
        )
    }

    @Test
    fun `cloud discovery requires unique default package class and exact static shapes`() {
        val cloud = cloudDescriptor("opaque")
        val packaged = cloudDescriptor("some.package.opaque")
        val partial = descriptor(
            "partialCloud",
            method("partialCloud", "onlyInt", returnType = INT)
        )

        val result = StructuralHookDiscovery.discoverCloud(listOf(packaged, partial, cloud))

        assertEquals(DiscoveryStatus.DISCOVERED, result.status)
        assertEquals(
            cloud.methods.single { it.parameterTypes.isEmpty() && it.returnType == INT },
            result.target
        )

        val ambiguous = StructuralHookDiscovery.discoverCloud(
            listOf(cloud, cloudDescriptor("another"))
        )
        assertEquals(DiscoveryStatus.AMBIGUOUS, ambiguous.status)
        assertEquals(2, ambiguous.conflicts.size)
    }

    @Test
    fun `cloud discovery rejects approximate return parameter and static shapes`() {
        val className = "nearCloud"
        val near = descriptor(
            className,
            method(className, "a", listOf(BOOLEAN), "kotlin.collections.Map"),
            method(className, "b", listOf(MAP, LONG), INTEGER),
            method(className, "c", returnType = INT, isStatic = false),
            method(className, "d", listOf(CONTEXT), "java.lang.Boolean")
        )

        val result = StructuralHookDiscovery.discoverCloud(listOf(near))

        assertEquals(DiscoveryStatus.REJECTED, result.status)
        assertNull(result.target)
        assertEquals(4, result.rejectedCandidates.size)
    }

    @Test
    fun `each failed capability is isolated from all other discoveries`() {
        val fixture = fixtureFor(898)
        val removals = listOf(
            WORD_CLASS to { result: StructuralDiscoveryResult -> result.wordLimit.status },
            "n48" to { result: StructuralDiscoveryResult -> result.display.status },
            PRIVILEGE_CLASS to { result: StructuralDiscoveryResult -> result.privilege.status },
            "y95" to { result: StructuralDiscoveryResult -> result.cloud.status }
        )

        removals.forEach { (removedClass, failedStatus) ->
            val result = StructuralHookDiscovery.discover(
                fixture.classes.filterNot { it.className == removedClass },
                fixture.wordLimitResults
            )
            assertEquals(DiscoveryStatus.MISSING, failedStatus(result))
            val statuses = listOf(
                result.wordLimit.status,
                result.display.status,
                result.privilege.status,
                result.cloud.status
            )
            assertEquals(3, statuses.count { it == DiscoveryStatus.DISCOVERED })
            assertEquals(3, result.targets.presentCapabilityCount)
        }
    }

    private data class Fixture(
        val classes: List<ClassDescriptor>,
        val wordLimitResults: Map<MethodSignature, Int>
    )

    private fun fixtureFor(versionCode: Int): Fixture {
        val builtIn = requireNotNull(HookTargets.builtIn(versionCode))
        val wordClass = descriptor(
            WORD_CLASS,
            method(WORD_CLASS, "q", returnType = INT),
            requireNotNull(builtIn.wordLimit)
        )
        val display = requireNotNull(builtIn.display)
        val privilege = requireNotNull(builtIn.privilege)
        val cloud = requireNotNull(builtIn.cloud)
        val classes = listOf(
            wordClass,
            descriptor(display.twoArgumentMethod.className, *display.methods.toTypedArray()),
            descriptor(PRIVILEGE_CLASS, *privilege.methods.toTypedArray()),
            cloudDescriptor(cloud.className, cloud)
        )
        val results = mapOf(
            wordClass.methods.first { it.methodName == "q" } to 37_515_930,
            requireNotNull(builtIn.wordLimit) to if (versionCode == 893) 669 else 670
        )
        return Fixture(classes, results)
    }

    private fun displayDescriptor(
        className: String,
        shortName: String,
        detailedName: String
    ): ClassDescriptor = descriptor(
        className,
        method(className, shortName, DISPLAY_SHORT, VOID),
        method(className, detailedName, DISPLAY_DETAILED, VOID)
    )

    private fun privilegeDescriptor(versionCode: Int): ClassDescriptor {
        val target = requireNotNull(HookTargets.builtIn(versionCode)?.privilege)
        return descriptor(PRIVILEGE_CLASS, *target.methods.toTypedArray())
    }

    private fun cloudDescriptor(
        className: String,
        accessor: MethodSignature = method(className, "quota", returnType = INT)
    ): ClassDescriptor = descriptor(
        className,
        method(className, "map", listOf(BOOLEAN), MAP),
        method(className, "index", listOf(MAP, INT), INTEGER),
        accessor,
        method(className, "available", listOf(CONTEXT), BOOLEAN)
    )

    private fun descriptor(
        className: String,
        vararg methods: MethodSignature
    ): ClassDescriptor = ClassDescriptor(className, methods.toList())

    private fun method(
        className: String,
        methodName: String,
        parameterTypes: List<String> = emptyList(),
        returnType: String,
        isStatic: Boolean = true
    ): MethodSignature = MethodSignature(
        className,
        methodName,
        parameterTypes,
        returnType,
        isStatic
    )

    private companion object {
        const val WORD_CLASS = "com.maimemo.android.momo.a"
        const val PRIVILEGE_CLASS = "com.maimemo.android.momo.user.level.a"
        const val PRIVILEGE_CODE = "com.maimemo.android.momo.user.level.PrivilegeCode"
        const val TEXT_VIEW = "android.widget.TextView"
        const val CONTEXT = "android.content.Context"
        const val MAP = "java.util.Map"
        const val INTEGER = "java.lang.Integer"
        const val VOID = "void"
        const val BOOLEAN = "boolean"
        const val INT = "int"
        const val LONG = "long"
        const val FLOAT = "float"

        val DISPLAY_SHORT = listOf(TEXT_VIEW, INT)
        val DISPLAY_DETAILED = listOf(TEXT_VIEW, INT, BOOLEAN, FLOAT)
    }
}
