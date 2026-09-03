package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AesGcmSecretBoxTest
{
    @Test
    fun encrypt_decrypt()
    {
        val keyFile = Files.createTempDirectory("key").resolve("panel.notify.key")
        val box = AesGcmSecretBox(keyFile)
        assertEquals("secret-token", box.decrypt(box.encrypt("secret-token")))
        assertTrue(Files.isRegularFile(keyFile))
        assertEquals(32, Files.size(keyFile))
    }
}
