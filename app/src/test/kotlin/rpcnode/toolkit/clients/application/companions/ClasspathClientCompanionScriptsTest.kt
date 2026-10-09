package rpcnode.toolkit.clients.application.companions

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId

class ClasspathClientCompanionScriptsTest
{
    @Test
    fun ships_solana_scripts_into_clients_dir()
    {
        val dir = Files.createTempDirectory("solana-companions")
        val written = ClasspathClientCompanionScripts().ship(NetworkId.SOLANA, dir)
        assertTrue(written.contains("run-validator.sh.tmpl"), written.toString())
        assertTrue(written.contains("build-agave.sh.tmpl"), written.toString())
        assertTrue(Files.isRegularFile(dir.resolve("run-validator.sh.tmpl")))
        assertTrue(Files.readString(dir.resolve("run-validator.sh.tmpl")).contains("{{bin}}"))
    }

    @Test
    fun other_networks_ship_nothing()
    {
        val dir = Files.createTempDirectory("tron-companions")
        val written = ClasspathClientCompanionScripts().ship(NetworkId.TRON, dir)
        assertTrue(written.isEmpty())
    }
}
