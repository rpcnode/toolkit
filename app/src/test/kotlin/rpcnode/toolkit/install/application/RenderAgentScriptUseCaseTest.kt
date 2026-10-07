package rpcnode.toolkit.install.application

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderAgentScriptUseCaseTest
{
    @Test
    fun version_is_injected()
    {
        val dir = Files.createTempDirectory("install")
        val useCase = RenderAgentScriptUseCase(dir, agentVersion = "0.2.0")
        assertEquals("0.2.0", useCase.version())
    }

    @Test
    fun jar_is_the_unversioned_alias()
    {
        val dir = Files.createTempDirectory("install")
        val binaries = dir.resolve("binaries")
        Files.createDirectories(binaries)
        Files.writeString(binaries.resolve("rpcnode-agent.jar"), "latest")
        val useCase = RenderAgentScriptUseCase(dir)
        assertEquals("rpcnode-agent.jar", useCase.jar()!!.fileName.toString())
    }
}
