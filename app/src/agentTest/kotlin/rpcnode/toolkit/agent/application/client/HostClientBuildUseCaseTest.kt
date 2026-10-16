package rpcnode.toolkit.agent.application.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import rpcnode.toolkit.clients.infrastructure.docker.BuildProcessResult
import rpcnode.toolkit.clients.infrastructure.docker.BuildProcessRunner
import rpcnode.toolkit.clients.infrastructure.docker.DockerBuildRunner

class HostClientBuildUseCaseTest
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = Files.createTempDirectory("agent-builds")

    /** A docker that waits on [gate] inside `build`, then exports `xrpld` (or fails). */
    private class Docker(
        val gate: CountDownLatch = CountDownLatch(0),
        val buildExit: Int = 0,
        val daemonUp: Boolean = true,
        val buildx: Boolean = true,
    ) : BuildProcessRunner
    {
        var builds = 0

        override fun run(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit): BuildProcessResult
        {
            if (command[1] == "buildx")
            {
                return BuildProcessResult(if (buildx) 0 else 1, listOf("not a docker command"))
            }
            if (command[1] == "version")
            {
                return BuildProcessResult(if (daemonUp) 0 else 1, listOf("no daemon"))
            }
            builds++
            onLine("#7 compiling")
            gate.await(10, TimeUnit.SECONDS)
            val out = command[command.indexOf("--output") + 1].substringAfter("dest=")
            Files.writeString(Path.of(out).resolve("xrpld"), "ELF")
            return BuildProcessResult(buildExit, listOf("boom"))
        }
    }

    private fun useCase(
        docker: Docker,
        arch: String = "x86_64",
        buildx: DockerBuildxInstaller = DockerBuildxInstaller { null },
    ) = HostClientBuildUseCase(scope, DockerBuildRunner(runner = docker), root, hostArch = { arch }, buildx = buildx)

    private fun cmd(id: String = "xrpl-3.4.1-x86_64", platform: String = "linux/amd64") = HostBuildCommand(
        jobId = id, network = "xrpl", program = "xrpld", version = "3.4.1", tag = "3.4.1",
        platform = platform, dockerfile = "FROM scratch", outputs = listOf("xrpld"),
    )

    private fun await(uc: HostClientBuildUseCase, id: String = "xrpl-3.4.1-x86_64"): HostBuildStatus
    {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline)
        {
            val st = uc.status(id)
            if (st.phase != "running") return st
            Thread.sleep(20)
        }
        error("still running")
    }

    @Test
    fun builds_in_the_background_and_offers_the_finished_file()
    {
        val uc = useCase(Docker())
        assertEquals(HostBuildStart.Accepted, uc.start(cmd()))
        val done = await(uc)

        assertEquals("done", done.phase)
        assertEquals(listOf("xrpld-x86_64"), done.files)
        val file = assertNotNull(uc.artifact("xrpl-3.4.1-x86_64", "xrpld-x86_64"))
        assertEquals("ELF", Files.readString(file))
        assertNull(uc.artifact("xrpl-3.4.1-x86_64", "../../etc/passwd"), "only published names are served")
        uc.release("xrpl-3.4.1-x86_64")
        assertNull(uc.artifact("xrpl-3.4.1-x86_64", "xrpld-x86_64"))
        assertEquals("unknown", uc.status("xrpl-3.4.1-x86_64").phase)
    }

    @Test
    fun one_compile_at_a_time_and_a_repeated_start_re_attaches()
    {
        val gate = CountDownLatch(1)
        val docker = Docker(gate)
        val uc = useCase(docker)

        assertEquals(HostBuildStart.Accepted, uc.start(cmd()))
        assertEquals(HostBuildStart.Accepted, uc.start(cmd()), "the panel restarted and asks again")
        assertIs<HostBuildStart.Busy>(uc.start(cmd(id = "other-job")))
        assertEquals("running", uc.status("xrpl-3.4.1-x86_64").phase)

        gate.countDown()
        assertEquals("done", await(uc).phase)
        assertEquals(1, docker.builds)
    }

    @Test
    fun a_failed_job_reports_why_and_can_be_started_again()
    {
        val uc = useCase(Docker(buildExit = 1))
        uc.start(cmd())
        val failed = await(uc)
        assertEquals("failed", failed.phase)
        assertTrue(failed.detail.contains("boom"), failed.detail)
        assertEquals(HostBuildStart.Accepted, uc.start(cmd()), "a failed job is replaced, not stuck")
    }

    @Test
    fun a_host_that_cannot_build_says_so_instead_of_pretending()
    {
        assertIs<HostBuildStart.Unavailable>(useCase(Docker(daemonUp = false)).start(cmd()))
        // no buildx plugin and it cannot be installed: the job ends "unavailable" so the panel tries another host
        val uc = useCase(Docker(buildx = false), buildx = DockerBuildxInstaller { "agent is not root" })
        assertEquals(HostBuildStart.Accepted, uc.start(cmd(id = "nobuildx")))
        val st = await(uc, "nobuildx")
        assertEquals("unavailable", st.phase)
        assertTrue(st.detail.contains("not root"), st.detail)
        val arm = useCase(Docker(), arch = "aarch64").start(cmd(platform = "linux/amd64"))
        assertTrue(assertIs<HostBuildStart.Unavailable>(arm).detail.contains("natively"))
    }

    @Test
    fun the_full_build_log_is_written_on_the_host_and_outlives_the_job()
    {
        val uc = useCase(Docker(buildExit = 1))
        uc.start(cmd())
        val st = await(uc)
        assertEquals("failed", st.phase)

        val file = Path.of(st.logFile)
        assertTrue(Files.isRegularFile(file), "the log lives next to the build dir: ${st.logFile}")
        val text = Files.readString(file)
        assertTrue(text.contains("client build xrpld 3.4.1") && text.contains("#7 compiling") && text.contains("finished: failed"), text)

        uc.release("xrpl-3.4.1-x86_64")
        assertEquals("unknown", uc.status("xrpl-3.4.1-x86_64").phase)
        assertTrue(Files.isRegularFile(file), "release removes the files, not the log")
        assertTrue(uc.logTail("xrpl-3.4.1-x86_64", 2)!!.last().contains("finished: failed"))
        assertNull(uc.logTail("../../etc/passwd"), "only job ids name a log")
    }

    @Test
    fun a_missing_buildx_plugin_is_installed_before_the_build_runs()
    {
        var installs = 0
        val docker = Docker(buildx = false)
        val uc = useCase(docker, buildx = DockerBuildxInstaller { installs++; null })
        assertEquals(HostBuildStart.Accepted, uc.start(cmd()))
        assertEquals("done", await(uc).phase)
        assertEquals(1, installs)
        assertEquals(1, docker.builds)
    }

    @Test
    fun rejects_ids_and_outputs_that_could_escape_the_build_directory()
    {
        val uc = useCase(Docker())
        assertIs<HostBuildStart.Invalid>(uc.start(cmd(id = "../evil")))
        assertIs<HostBuildStart.Invalid>(uc.start(cmd().copy(outputs = listOf("../x"))))
        assertIs<HostBuildStart.Invalid>(uc.start(cmd().copy(dockerfile = " ")))
        assertIs<HostBuildStart.Invalid>(uc.start(cmd(platform = "linux/riscv64")))
    }
}
