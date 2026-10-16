package rpcnode.toolkit.agent.application.client

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.clients.infrastructure.docker.BuildProcessResult
import rpcnode.toolkit.clients.infrastructure.docker.BuildProcessRunner
import rpcnode.toolkit.clients.infrastructure.docker.DockerBuildRunner

class GithubBuildxInstallerTest
{
    /** `docker buildx version` works only once the plugin file exists. */
    private class Docker(private val plugin: Path) : BuildProcessRunner
    {
        override fun run(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit): BuildProcessResult =
            BuildProcessResult(if (command[1] == "buildx" && !Files.exists(plugin)) 1 else 0, emptyList())
    }

    private val dir = Files.createTempDirectory("cli-plugins")
    private val target = dir.resolve("docker-buildx")
    private val payload = "buildx-binary".toByteArray()
    private val goodSha = run {
        val f = Files.createTempFile("sha", ".bin"); Files.write(f, payload); GithubBuildxInstaller.sha256(f)
    }

    private fun installer(
        root: Boolean = true,
        arch: String = "amd64",
        sha: String = goodSha,
        urls: MutableList<String> = mutableListOf(),
        content: ByteArray = payload,
    ) = GithubBuildxInstaller(
        docker = DockerBuildRunner(runner = Docker(target)),
        isRoot = { root },
        pluginDir = dir,
        hostArch = { arch },
        download = { url, dest -> urls += url; Files.write(dest, content) },
        checksums = mapOf("amd64" to sha, "arm64" to sha),
    )

    @Test
    fun downloads_the_pinned_release_verifies_it_and_installs_it_as_a_docker_plugin()
    {
        val urls = mutableListOf<String>()
        assertNull(installer(urls = urls).install {})
        assertEquals(listOf("https://github.com/docker/buildx/releases/download/v${GithubBuildxInstaller.VERSION}/buildx-v${GithubBuildxInstaller.VERSION}.linux-amd64"), urls)
        assertTrue(Files.isExecutable(target))
        assertEquals("buildx-binary", Files.readString(target))
        assertFalse(Files.exists(dir.resolve("docker-buildx.part")))
    }

    @Test
    fun picks_the_arm64_build_on_an_arm_host()
    {
        val urls = mutableListOf<String>()
        installer(arch = "aarch64", urls = urls).install {}
        assertTrue(urls.single().endsWith("linux-arm64"), urls.toString())
    }

    @Test
    fun a_download_that_does_not_match_the_pinned_checksum_is_refused_and_removed()
    {
        val reason = installer(content = "tampered".toByteArray()).install {}
        assertTrue(reason.orEmpty().contains("checksum"), reason)
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(dir.resolve("docker-buildx.part")))
    }

    @Test
    fun without_root_it_explains_instead_of_trying()
    {
        val urls = mutableListOf<String>()
        assertTrue(installer(root = false, urls = urls).install {}.orEmpty().contains("not root"))
        assertTrue(urls.isEmpty())
    }

    @Test
    fun does_nothing_when_buildx_already_works()
    {
        Files.writeString(target, "already")
        val urls = mutableListOf<String>()
        assertNull(installer(urls = urls).install {})
        assertTrue(urls.isEmpty())
        assertEquals("already", Files.readString(target))
    }

    @Test
    fun the_pinned_checksums_cover_both_architectures_and_look_like_sha256()
    {
        assertEquals(setOf("amd64", "arm64"), GithubBuildxInstaller.SHA256.keys)
        assertTrue(GithubBuildxInstaller.SHA256.values.all { Regex("[0-9a-f]{64}").matches(it) })
    }
}
