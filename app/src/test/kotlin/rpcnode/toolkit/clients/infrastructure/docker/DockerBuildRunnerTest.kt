package rpcnode.toolkit.clients.infrastructure.docker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import rpcnode.toolkit.clients.application.ClientBuildResult

class DockerBuildRunnerTest
{
    /** Pretends to be docker: `version` succeeds, `build` exports [produce] into the `--output` directory. */
    private class FakeDocker(
        private val versionExit: Int = 0,
        private val buildxExit: Int = 0,
        private val buildExit: Int = 0,
        private val produce: List<String> = listOf("xrpld"),
    ) : BuildProcessRunner
    {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit): BuildProcessResult
        {
            commands += command
            if (command[1] == "buildx")
            {
                return BuildProcessResult(buildxExit, listOf("docker: 'buildx' is not a docker command"))
            }
            if (command[1] == "version")
            {
                return BuildProcessResult(versionExit, if (versionExit == 0) listOf("27.0") else listOf("Cannot connect to the Docker daemon"))
            }
            onLine("#5 [build 3/9] RUN conan install")
            val out = command[command.indexOf("--output") + 1].substringAfter("dest=")
            produce.forEach { Files.writeString(Path.of(out).resolve(it), "ELF-$it") }
            return BuildProcessResult(buildExit, listOf("error: linker failed"))
        }
    }

    private val out = Files.createTempDirectory("runner-out")

    @Test
    fun builds_one_platform_with_the_release_as_build_args_and_names_the_file_after_the_arch()
    {
        val docker = FakeDocker()
        val lines = mutableListOf<String>()
        val result = DockerBuildRunner(runner = docker).build(
            "FROM scratch", "linux/arm64", "3.4.1", "3.4.1", listOf("xrpld"), out,
        ) { lines += it }

        assertEquals(ClientBuildResult.Built(listOf("xrpld-aarch64")), result)
        assertEquals("ELF-xrpld", Files.readString(out.resolve("xrpld-aarch64")))
        assertTrue(Files.isExecutable(out.resolve("xrpld-aarch64")))
        val cmd = docker.commands.last()
        assertEquals(listOf("--platform", "linux/arm64"), cmd.subList(2, 4))
        assertContains(cmd, "VERSION=3.4.1")
        assertContains(cmd, "TAG=3.4.1")
        assertContains(lines, "#5 [build 3/9] RUN conan install")
    }

    @Test
    fun a_build_arg_that_could_smuggle_a_flag_is_refused_without_running_docker()
    {
        val docker = FakeDocker()
        val result = DockerBuildRunner(runner = docker).build("FROM scratch", "linux/amd64", "1.0 --privileged", "v1", listOf("x"), out) {}
        assertIs<ClientBuildResult.Failed>(result)
        assertTrue(docker.commands.isEmpty())
    }

    @Test
    fun a_failing_build_reports_the_tail_of_the_output()
    {
        val result = DockerBuildRunner(runner = FakeDocker(buildExit = 1)).build("FROM scratch", "linux/amd64", "1", "v1", listOf("xrpld"), out) {}
        val failed = assertIs<ClientBuildResult.Failed>(result)
        assertContains(failed.error, "linker failed")
    }

    @Test
    fun an_output_the_dockerfile_did_not_export_is_a_failure()
    {
        val result = DockerBuildRunner(runner = FakeDocker(produce = emptyList())).build("FROM scratch", "linux/amd64", "1", "v1", listOf("xrpld"), out) {}
        assertContains(assertIs<ClientBuildResult.Failed>(result).error, "xrpld")
    }

    @Test
    fun problem_explains_why_docker_cannot_be_used()
    {
        assertEquals(null, DockerBuildRunner(runner = FakeDocker()).problem())
        assertContains(DockerBuildRunner(runner = FakeDocker(versionExit = 1)).problem().orEmpty(), "daemon")
        assertContains(DockerBuildRunner(runner = FakeDocker(buildxExit = 1)).problem().orEmpty(), "buildx")
        val missing = BuildProcessRunner { _, _, _ -> throw java.io.IOException("No such file: docker") }
        assertContains(DockerBuildRunner(runner = missing).problem().orEmpty(), "docker CLI")
    }
}
