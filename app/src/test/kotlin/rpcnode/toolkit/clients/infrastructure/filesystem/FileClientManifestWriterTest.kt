package rpcnode.toolkit.clients.infrastructure.filesystem

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileClientManifestWriterTest
{
    @Test
    fun multi_program_writes_composite_version() = runTest {
        val dir = Files.createTempDirectory("clients-manifest")
        val writer = FileClientManifestWriter()

        writer.write(
            dir = dir,
            network = "ethereum",
            env = "sepolia",
            program = "geth",
            version = "1.17.5",
            tag = "v1.17.5",
            source = "ethereum/go-ethereum",
            notes = "",
            files = emptyList(),
        )
        assertEquals("1.17.5", Files.readString(dir.resolve("VERSION")).trim())

        writer.write(
            dir = dir,
            network = "ethereum",
            env = "sepolia",
            program = "lighthouse",
            version = "8.2.2",
            tag = "v8.2.2",
            source = "sigp/lighthouse",
            notes = "",
            files = emptyList(),
        )

        assertEquals("geth 1.17.5 · lighthouse 8.2.2", Files.readString(dir.resolve("VERSION")).trim())
        val manifest = Json.parseToJsonElement(Files.readString(dir.resolve("manifest.json"))).jsonObject
        val programs = manifest["programs"]!!.jsonObject
        assertTrue(programs.containsKey("geth"))
        assertTrue(programs.containsKey("lighthouse"))
        assertEquals("1.17.5", programs["geth"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals("8.2.2", programs["lighthouse"]!!.jsonObject["version"]!!.jsonPrimitive.content)
    }
}
