package top.cyclops.roomseed.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcuExtensionTest {
    @Test fun mapsSupportedHostsAndRejectsUnsupportedArchitectures() {
        for (arch in listOf("arm64", "aarch64")) {
            assertEquals("macos-aarch64/libroomseedicu.dylib", IcuExtension.resourcePath("Mac OS X", arch))
            assertEquals("linux-aarch64/libroomseedicu.so", IcuExtension.resourcePath("Linux", arch))
        }
        for (arch in listOf("amd64", "x86_64")) {
            assertEquals("macos-x86_64/libroomseedicu.dylib", IcuExtension.resourcePath("Mac OS X", arch))
            assertEquals("linux-x86_64/libroomseedicu.so", IcuExtension.resourcePath("Linux", arch))
            assertEquals("windows-x86_64/roomseedicu.dll", IcuExtension.resourcePath("Windows 11", arch))
        }
        assertNull(IcuExtension.resourcePath("Windows 11", "aarch64"))
        assertNull(IcuExtension.resourcePath("Linux", "riscv64"))
        assertNull(IcuExtension.resourcePath("FreeBSD", "amd64"))
    }
}
