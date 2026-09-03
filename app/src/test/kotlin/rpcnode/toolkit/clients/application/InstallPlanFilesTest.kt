package rpcnode.toolkit.clients.application

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallPlanFilesTest
{
    @Test
    fun includes_version_when_pin_file_exists()
    {
        val dir = Files.createTempDirectory("install-plan")
        Files.writeString(dir.resolve("VERSION"), "4.8.2\n")
        Files.writeString(dir.resolve("FullNode.jar"), "jar")
        val files = listOf(InstallPlanFile(role = "artifact", path = "FullNode.jar"))
        val got = installPlanFilesIncludingVersion(dir, files)
        assertEquals(2, got.size)
        assertTrue(got.any { it.path == "VERSION" && it.role == "version" })
    }

    @Test
    fun skips_version_when_pin_file_missing()
    {
        val dir = Files.createTempDirectory("install-plan-no-ver")
        val files = listOf(InstallPlanFile(role = "artifact", path = "FullNode.jar"))
        val got = installPlanFilesIncludingVersion(dir, files)
        assertEquals(1, got.size)
    }

    @Test
    fun append_version_plan_file_is_idempotent()
    {
        val once = appendVersionPlanFile(emptyList())
        val twice = appendVersionPlanFile(once)
        assertEquals(once, twice)
    }
}
