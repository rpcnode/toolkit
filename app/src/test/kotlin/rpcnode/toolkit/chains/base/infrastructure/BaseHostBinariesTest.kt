package rpcnode.toolkit.chains.base.infrastructure

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BaseHostBinariesTest
{
    @Test
    fun finds_binary_in_parent_of_execution_dir()
    {
        val root = Files.createTempDirectory("base-bins")
        val network = root.resolve("mainnet")
        val execution = network.resolve("execution")
        Files.createDirectories(execution)
        val reth = network.resolve("base-reth-node")
        val cons = network.resolve("base-consensus")
        Files.writeString(reth, "#!/bin/true\n")
        Files.writeString(cons, "#!/bin/true\n")
        setExec(reth)
        setExec(cons)

        val result = assertIs<BaseHostBinaries.Result.Ok>(BaseHostBinaries.ensure("mainnet", execution))
        assertTrue(Files.isExecutable(result.bins.reth))
        assertTrue(Files.isExecutable(result.bins.consensus))
        assertEquals("base-reth-node", result.bins.reth.fileName.toString())
    }

    private fun setExec(path: java.nio.file.Path)
    {
        try
        {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        catch (_: Exception)
        {
            path.toFile().setExecutable(true)
        }
    }
}
