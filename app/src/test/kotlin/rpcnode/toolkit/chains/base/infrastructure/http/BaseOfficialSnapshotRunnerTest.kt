package rpcnode.toolkit.chains.base.infrastructure.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class BaseOfficialSnapshotRunnerTest
{
    @Test
    fun heal_script_includes_manifest_url_flag_for_cdn()
    {
        val dir = Files.createTempDirectory("base-heal")
        val reth = dir.resolve("base-reth-node")
        Files.writeString(reth, "#!/bin/true\n")
        reth.toFile().setExecutable(true)
        val runner = BaseOfficialSnapshotRunner()
        val paths = BaseOfficialSnapshotRunner.Paths(
            data = dir.resolve("data"),
            opt = dir.resolve("opt"),
            marker = dir.resolve("data/.snapshot-ready"),
            state = dir.resolve("data/.snapshot-state.json"),
            logFile = dir.resolve("log/snapshot.log"),
            script = dir.resolve("opt/bin/base-official-snapshot.sh"),
        )
        val script = runner.writeHealScript(
            paths = paths,
            env = "sepolia",
            flavor = "full",
            rethBin = reth,
            manifestUrl = "http://cdn.example:8095/snapshots/base/sepolia/archive/1/manifest.json",
        )
        val body = Files.readString(script)
        assertTrue(body.contains("MANIFEST_URL='http://cdn.example:8095/snapshots/base/sepolia/archive/1/manifest.json'"))
        assertTrue(body.contains("--manifest-url \"\$MANIFEST_URL\""))
        assertTrue(body.contains("download --\"\$FLAVOR\""))
    }

    @Test
    fun heal_script_omits_manifest_url_for_official()
    {
        val dir = Files.createTempDirectory("base-heal-off")
        val reth = dir.resolve("base-reth-node")
        Files.writeString(reth, "#!/bin/true\n")
        reth.toFile().setExecutable(true)
        val runner = BaseOfficialSnapshotRunner()
        val paths = BaseOfficialSnapshotRunner.Paths(
            data = dir.resolve("data"),
            opt = dir.resolve("opt"),
            marker = dir.resolve("data/.snapshot-ready"),
            state = dir.resolve("data/.snapshot-state.json"),
            logFile = dir.resolve("log/snapshot.log"),
            script = dir.resolve("opt/bin/base-official-snapshot.sh"),
        )
        val script = runner.writeHealScript(
            paths = paths,
            env = "mainnet",
            flavor = "archive",
            rethBin = reth,
            manifestUrl = null,
        )
        val body = Files.readString(script)
        assertTrue(body.contains("MANIFEST_URL=''"))
        assertTrue(body.contains("if [ -n \"\$MANIFEST_URL\" ]; then"))
    }
}
