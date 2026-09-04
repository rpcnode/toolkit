package rpcnode.toolkit.setup.application.check

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.settings.FakeSettingsStore
import rpcnode.toolkit.settings.application.get.InstallFiles
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin

class RunSetupCheckUseCaseTest
{
    @Test
    fun ready_when_required_pass() = runTest {
        val dir = Files.createTempDirectory("setup-check")
        val db = dir.resolve("toolkit.db")
        Files.writeString(db, "")
        val store = FakeSettingsStore()
        store.setInstallOrigin((InstallOrigin.parse("http://10.0.0.2:8094") as InstallOrigin.Parse.Ok).origin)
        val useCase = RunSetupCheckUseCase(
            store = store,
            probe = UrlProbe { true },
            installFiles = InstallFiles { false },
            dbPath = db,
        )
        val result = useCase()
        assertTrue(result.ready)
        assertEquals(true, result.checks.first { it.id == "server" }.ok)
        assertEquals(true, result.checks.first { it.id == "sqlite" }.ok)
        assertFalse(result.checks.first { it.id == "binaries" }.required)
    }

    @Test
    fun not_ready_without_origin() = runTest {
        val dir = Files.createTempDirectory("setup-check")
        val db = dir.resolve("toolkit.db")
        Files.writeString(db, "")
        val useCase = RunSetupCheckUseCase(
            store = FakeSettingsStore(),
            probe = UrlProbe { true },
            installFiles = InstallFiles { true },
            dbPath = db,
        )
        val result = useCase()
        assertFalse(result.ready)
        assertFalse(result.checks.first { it.id == "server" }.ok)
    }

    @Test
    fun cdn_optional_when_unset() = runTest {
        val dir = Files.createTempDirectory("setup-check")
        val db = dir.resolve("toolkit.db")
        Files.writeString(db, "")
        val store = FakeSettingsStore()
        store.setInstallOrigin((InstallOrigin.parse("http://10.0.0.2:8094") as InstallOrigin.Parse.Ok).origin)
        val useCase = RunSetupCheckUseCase(
            store = store,
            probe = UrlProbe { url -> !url.contains(":8095") },
            installFiles = InstallFiles { true },
            dbPath = db,
        )
        val result = useCase()
        assertTrue(result.ready)
        assertTrue(result.checks.first { it.id == "cdn" }.ok)
    }

    @Test
    fun cdn_reports_down_when_set() = runTest {
        val dir = Files.createTempDirectory("setup-check")
        val db = dir.resolve("toolkit.db")
        Files.writeString(db, "")
        val store = FakeSettingsStore()
        store.setInstallOrigin((InstallOrigin.parse("http://10.0.0.2:8094") as InstallOrigin.Parse.Ok).origin)
        store.setSnapshotCdnOrigin((SnapshotCdnOrigin.parse("http://10.0.0.2:8095") as SnapshotCdnOrigin.Parse.Ok).origin)
        val useCase = RunSetupCheckUseCase(
            store = store,
            probe = UrlProbe { url -> url.contains("/healthz") },
            installFiles = InstallFiles { true },
            dbPath = db,
        )
        val result = useCase()
        assertTrue(result.ready)
        assertFalse(result.checks.first { it.id == "cdn" }.ok)
    }
}
