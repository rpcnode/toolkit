package rpcnode.toolkit.agent.application.reserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeReservedPortsHost(
    private val files: MutableMap<String, String> = mutableMapOf(),
    var refuseProc: Boolean = false,
) : ReservedPortsHost
{
    var runs: Int = 0

    override fun readFile(path: String): String? = files[path]

    override fun writeFile(path: String, data: String): Boolean
    {
        if (refuseProc && path == ReserveAgentPortsUseCase.RESERVED_PORTS_PROC)
        {
            return false
        }
        files[path] = data
        return true
    }

    override fun mkdirAll(path: String)
    {
    }

    override fun run(name: String, vararg args: String): Boolean
    {
        runs += 1
        if (name != "sysctl" || args.size != 2 || args[0] != "-w")
        {
            return false
        }
        if (refuseProc)
        {
            return false
        }
        val value = args[1].substringAfter("=", "")
        files[ReserveAgentPortsUseCase.RESERVED_PORTS_PROC] = value
        return true
    }
}

class ReserveAgentPortsUseCaseTest
{
    private fun host(portRange: String, reserved: String = ""): FakeReservedPortsHost
    {
        return FakeReservedPortsHost(
            mutableMapOf(
                ReserveAgentPortsUseCase.LOCAL_PORT_RANGE_PROC to portRange,
                ReserveAgentPortsUseCase.RESERVED_PORTS_PROC to reserved,
            ),
        )
    }

    @Test
    fun applies_and_persists()
    {
        val fake = host("32768 60999")
        val st = ReserveAgentPortsUseCase(fake, listOf(8332, 39490, 39491))()
        assertTrue(st.checked && st.ok && st.applied)
        assertEquals("39490-39491", fake.readFile(ReserveAgentPortsUseCase.RESERVED_PORTS_PROC))
        val conf = fake.readFile(ReserveAgentPortsUseCase.DEFAULT_CONF_PATH).orEmpty()
        assertTrue(conf.contains("${ReserveAgentPortsUseCase.SYSCTL_KEY} = 39490-39491"))
        assertFalse("8332" in conf)
        assertEquals("8332,39490-39491\n", fake.readFile(ReserveAgentPortsUseCase.DEFAULT_RANGE_FILE))
    }

    @Test
    fun second_pass_is_a_noop()
    {
        val fake = host("32768 60999")
        val first = ReserveAgentPortsUseCase(fake, listOf(39490))()
        assertTrue(first.applied)
        val runs = fake.runs
        val second = ReserveAgentPortsUseCase(fake, listOf(39490))()
        assertTrue(second.ok)
        assertFalse(second.applied)
        assertEquals(runs, fake.runs)
    }

    @Test
    fun rewrites_stale_drop_in()
    {
        val fake = host("32768 60999", "39490")
        val st = ReserveAgentPortsUseCase(fake, listOf(39490))()
        assertTrue(st.ok && st.applied)
        assertTrue("39490" in fake.readFile(ReserveAgentPortsUseCase.DEFAULT_CONF_PATH).orEmpty())
    }

    @Test
    fun keeps_operator_reservations()
    {
        val fake = host("32768 60999", "45000-45002")
        ReserveAgentPortsUseCase(fake, listOf(39490))()
        assertEquals("39490,45000-45002", fake.readFile(ReserveAgentPortsUseCase.RESERVED_PORTS_PROC))
    }

    @Test
    fun fails_when_kernel_refuses()
    {
        val fake = host("32768 60999")
        fake.refuseProc = true
        val st = ReserveAgentPortsUseCase(fake, listOf(39490, 39491))()
        assertTrue(st.checked)
        assertFalse(st.ok)
        assertEquals(2, st.missing.size)
        assertTrue("refused" in st.detail)
    }

    @Test
    fun not_applicable_without_procfs()
    {
        val fake = FakeReservedPortsHost()
        val st = ReserveAgentPortsUseCase(fake, listOf(39490))()
        assertFalse(st.checked)
        assertFalse(st.ok)
        assertEquals(0, fake.runs)
    }
}
