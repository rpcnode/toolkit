package rpcnode.toolkit.settings.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InstallOriginTest
{
    @Test
    fun parse_table()
    {
        val tests = listOf(
            "" to InstallOrigin.Parse.Empty,
            "   " to InstallOrigin.Parse.Empty,
            "not-a-url" to InstallOrigin.Parse.Invalid,
            "ftp://example.com" to InstallOrigin.Parse.Invalid,
            "http://" to InstallOrigin.Parse.Invalid,
        )
        for ((give, want) in tests)
        {
            assertEquals(want, InstallOrigin.parse(give), give)
        }
    }

    @Test
    fun strips_slash_and_install_suffix()
    {
        val parsed = assertIs<InstallOrigin.Parse.Ok>(
            InstallOrigin.parse("http://127.0.0.1:8093/install/"),
        )
        assertEquals("http://127.0.0.1:8093", parsed.origin.value)
    }

    @Test
    fun rewrites_legacy_8095_to_local()
    {
        val parsed = assertIs<InstallOrigin.Parse.Ok>(InstallOrigin.parse("http://localhost:8095/"))
        assertEquals(InstallOrigin.LOCAL, parsed.origin.value)
    }

    @Test
    fun channel_urls()
    {
        val origin = assertIs<InstallOrigin.Parse.Ok>(InstallOrigin.parse(InstallOrigin.PROD)).origin
        val ch = origin.channel()
        assertEquals(InstallOrigin.PROD, ch.installOrigin)
        assertEquals(InstallOrigin.PROD, ch.clientsBaseUrl)
        assertEquals("${InstallOrigin.PROD}/install", ch.installBaseUrl)
        assertEquals("${InstallOrigin.PROD}/install/binaries/rpcnode-agent.jar", ch.agentDownloadUrl)
    }
}

class GitHubTokenTest
{
    @Test
    fun parse_empty()
    {
        assertNull(GitHubToken.parse("  "))
    }

    @Test
    fun masks_short_and_long()
    {
        assertEquals("••••", GitHubToken.parse("short")!!.masked)
        assertEquals("ghp_…cdef", GitHubToken.parse("ghp_abcdefghijklmnopcdef")!!.masked)
    }
}
