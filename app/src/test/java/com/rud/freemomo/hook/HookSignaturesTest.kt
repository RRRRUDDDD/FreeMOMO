package com.rud.freemomo.hook

import com.rud.freemomo.util.HookCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HookSignaturesTest {
    private val loader = SignatureFixture::class.java.classLoader!!
    private val exact = MethodSignature(SignatureFixture::class.java.name, "read", emptyList(), "int", true)

    @Test
    fun `resolver requires full declaring class parameters return and static identity`() {
        val method = HookSignatures.resolve(exact, loader)
        assertNotNull(method)
        assertEquals(exact, HookSignatures.signature(requireNotNull(method)))
        listOf(
            exact.copy(returnType = "java.lang.Integer"),
            exact.copy(isStatic = false),
            exact.copy(parameterTypes = listOf("int")),
            exact.copy(methodName = "missing"),
            exact.copy(className = "does.not.Exist")
        ).forEach { assertNull(HookSignatures.resolve(it, loader)) }
        assertNull(HookSignatures.resolveAll(listOf(exact, exact.copy(returnType = "long")), loader))
    }

    @Test
    fun `cache and installers share atomic privilege group validation`() {
        val known = requireNotNull(HookTargets.builtIn(898))
        val privilege = requireNotNull(known.privilege)
        val duplicate = privilege.copy(singleArgumentMethods = List(2) { privilege.singleArgumentMethods.first() })
        assertFalse(HookSignatures.isPrivilege(duplicate))
        val validated = HookCache.validate(known.copy(privilege = duplicate)) { true }
        assertNull(validated.privilege)
        assertEquals(known.display, validated.display)
        assertEquals(known.cloud, validated.cloud)
        assertEquals(known.wordLimit, validated.wordLimit)
    }

    @Test
    fun `893 898 and 900 exact mappings retain every business signature`() {
        listOf(893, 898, 900).forEach { version ->
            val targets = requireNotNull(HookTargets.builtIn(version))
            assertTrue(HookSignatures.isWord(requireNotNull(targets.wordLimit)))
            assertTrue(HookSignatures.isDisplay(requireNotNull(targets.display)))
            assertTrue(HookSignatures.isPrivilege(requireNotNull(targets.privilege)))
            assertTrue(HookSignatures.isCloud(requireNotNull(targets.cloud)))
            assertEquals(targets, HookCache.validate(targets) { true })
        }
    }

    @Test
    fun `primitive and array type names resolve without boxed substitutions`() {
        assertEquals(Int::class.javaPrimitiveType, HookSignatures.resolveType("int", loader))
        assertEquals(Boolean::class.javaPrimitiveType, HookSignatures.resolveType("boolean", loader))
        assertEquals(Void.TYPE, HookSignatures.resolveType("void", loader))
        assertEquals(IntArray::class.java, HookSignatures.resolveType("[I", loader))
        assertEquals(Array<String>::class.java, HookSignatures.resolveType("[Ljava.lang.String;", loader))
    }

    @Test
    fun `signature reflection never initializes the target class`() {
        assertFalse(InitializationState.initialized)
        val signature = exact.copy(className = LazySignatureFixture::class.java.name)
        assertNotNull(HookSignatures.resolve(signature, loader))
        assertFalse(InitializationState.initialized)
    }

    @Test
    fun `fatal loader failures are rethrown`() {
        val fatalLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "fatal.Type") throw OutOfMemoryError("fatal")
                return super.loadClass(name, resolve)
            }
        }
        assertThrows(OutOfMemoryError::class.java) {
            HookSignatures.resolve(exact.copy(className = "fatal.Type"), fatalLoader)
        }
    }
}

private class SignatureFixture {
    companion object {
        @JvmStatic fun read(): Int = 670
    }
}

private object InitializationState {
    var initialized = false
}

private class LazySignatureFixture {
    companion object {
        init { InitializationState.initialized = true }
        @JvmStatic fun read(): Int = 670
    }
}
