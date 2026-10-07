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

    private fun stageJar(dir: java.nio.file.Path, version: String?)
    {
        val binaries = dir.resolve("binaries")
        Files.createDirectories(binaries)
        java.util.zip.ZipOutputStream(Files.newOutputStream(binaries.resolve("rpcnode-agent.jar"))).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
            if (version != null)
            {
                zip.putNextEntry(java.util.zip.ZipEntry("agent/version"))
                zip.write("$version\n".toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun version_is_the_one_of_the_jar_that_is_actually_served()
    {
        // panel jar says 0.1.4, but the staged agent jar is a stale 0.1.3
        val dir = Files.createTempDirectory("install")
        stageJar(dir, "0.1.3")
        val useCase = RenderAgentScriptUseCase(dir, agentVersion = "0.1.4")
        assertEquals("0.1.3", useCase.version())
    }

    @Test
    fun version_follows_a_replaced_jar()
    {
        val dir = Files.createTempDirectory("install")
        stageJar(dir, "0.1.3")
        val useCase = RenderAgentScriptUseCase(dir, agentVersion = "0.1.4")
        assertEquals("0.1.3", useCase.version())
        Thread.sleep(20)
        stageJar(dir, "0.1.4-rc1")
        assertEquals("0.1.4-rc1", useCase.version())
    }

    @Test
    fun version_falls_back_to_the_panel_value_without_a_usable_jar()
    {
        val noJar = Files.createTempDirectory("install")
        assertEquals("0.1.4", RenderAgentScriptUseCase(noJar, agentVersion = "0.1.4").version())

        val brokenJar = Files.createTempDirectory("install")
        Files.createDirectories(brokenJar.resolve("binaries"))
        Files.writeString(brokenJar.resolve("binaries").resolve("rpcnode-agent.jar"), "not a zip")
        assertEquals("0.1.4", RenderAgentScriptUseCase(brokenJar, agentVersion = "0.1.4").version())

        val noVersionEntry = Files.createTempDirectory("install")
        stageJar(noVersionEntry, null)
        assertEquals("0.1.4", RenderAgentScriptUseCase(noVersionEntry, agentVersion = "0.1.4").version())
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
