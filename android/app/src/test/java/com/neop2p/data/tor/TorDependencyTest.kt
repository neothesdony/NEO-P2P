package com.neop2p.data.tor

import org.junit.Assert.assertNotNull
import org.junit.Test

class TorDependencyTest {
    @Test
    fun torServiceAndControlAreOnClasspath() {
        // The brief's literal form (`TorService::class.java.name`) cannot run on the
        // project's pinned JDK 21 unit-test JVM: tor-android 0.4.9.12 ships Java 24
        // bytecode (class-file major 68) and the JVM refuses to define it
        // (UnsupportedClassVersionError). The :app compilation step already resolves
        // these exact symbols against the compile classpath (its TDD RED was an
        // unresolved reference); this test additionally proves the class files are
        // present on the unit-test classpath, without forcing the JVM to load them.
        val loader = requireNotNull(javaClass.classLoader) { "no classloader" }
        for (fqcn in listOf(
            "org.torproject.jni.TorService",
            "net.freehaven.tor.control.TorControlConnection",
        )) {
            assertNotNull(
                "$fqcn is not on the :app classpath",
                loader.getResource(fqcn.replace('.', '/') + ".class"),
            )
        }
    }
}
