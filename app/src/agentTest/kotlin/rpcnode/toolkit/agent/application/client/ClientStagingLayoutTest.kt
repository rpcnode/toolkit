package rpcnode.toolkit.agent.application.client

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientStagingLayoutTest
{
    @Test
    fun snapshotPromoteAndRestore()
    {
        val root = Files.createTempDirectory("client-staging-test")
        try
        {
            Files.writeString(root.resolve("VERSION"), "1.0\n")
            Files.writeString(root.resolve("node.bin"), "old-bin")
            Files.writeString(root.resolve("main.conf"), "old-conf")

            val staging = ClientStagingLayout.updateDir(root)
            ClientStagingLayout.ensureEmptyDir(staging)
            Files.writeString(staging.resolve("VERSION"), "2.0\n")
            Files.writeString(staging.resolve("node.bin"), "new-bin")
            Files.writeString(staging.resolve("main.conf"), "new-conf")

            val previous = ClientStagingLayout.snapshotLiveToPrevious(
                root,
                ClientStagingLayout.listArtifactNames(staging),
            )
            assertEquals("1.0", previous)

            ClientStagingLayout.promoteStagingToLive(root, staging)
            assertEquals("2.0", Files.readString(root.resolve("VERSION")).trim())
            assertEquals("new-bin", Files.readString(root.resolve("node.bin")))

            val restored = ClientStagingLayout.restorePreviousToLive(root)
            assertEquals("1.0", restored)
            assertEquals("old-bin", Files.readString(root.resolve("node.bin")))
            assertTrue(Files.isRegularFile(ClientStagingLayout.previousDir(root).resolve("VERSION")))
        }
        finally
        {
            root.toFile().deleteRecursively()
        }
    }
}
