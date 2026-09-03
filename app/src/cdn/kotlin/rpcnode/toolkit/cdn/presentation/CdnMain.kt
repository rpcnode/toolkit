package rpcnode.toolkit.cdn.presentation

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import rpcnode.toolkit.cdn.application.sync.WatchSnapshotsUseCase
import rpcnode.toolkit.cdn.infrastructure.catalog.EmbeddedMirrorCatalog
import rpcnode.toolkit.cdn.infrastructure.filesystem.DiskMirrorStatusReader
import rpcnode.toolkit.cdn.infrastructure.filesystem.DiskSnapshotMirrorStore
import rpcnode.toolkit.cdn.infrastructure.filesystem.FileSnapshotTargetStore
import rpcnode.toolkit.cdn.infrastructure.http.LocalSnapshotSource

fun main(args: Array<String>)
{
    val version = CdnConfig.version()
    println("rpcnode-cdn $version")
    val cmd = args.firstOrNull()?.trim()?.lowercase().orEmpty()
    when (cmd)
    {
        "menu", "config", "targets" -> runMenu()
        "status", "st" -> runStatus()
        "install" -> kotlin.system.exitProcess(CdnSystemInstall.install())
        "uninstall", "remove" -> kotlin.system.exitProcess(CdnSystemInstall.uninstall())
        "sync", "run", "" -> runSync()
        "help", "-h", "--help" -> printHelp(version)
        else ->
        {
            System.err.println("unknown command: $cmd")
            printHelp(version)
            kotlin.system.exitProcess(2)
        }
    }
}

private fun printHelp(version: String)
{
    println(
        """
        rpcnode-cdn $version — Snapshot CDN sync (no panel)

          sudo java -jar rpcnode-cdn.jar install   # pick disk + systemd
          sudo java -jar rpcnode-cdn.jar uninstall # remove unit + jar (keeps snapshots)
          java -jar rpcnode-cdn.jar               # sync daemon (dir from env)
          java -jar rpcnode-cdn.jar menu           # targets + download directory
          java -jar rpcnode-cdn.jar status
          java -jar rpcnode-cdn.jar help

        SNAPSHOT_CDN_DIR is required. Default folder = current working directory.
        CDN_PUBLIC_ORIGIN is required to mirror Base V2 (rewrites manifest base_url).
        """.trimIndent(),
    )
}

private fun runMenu()
{
    val cfg = CdnBootstrap.load()
    val dir = ensureSnapshotDir(cfg) ?: kotlin.system.exitProcess(1)
    val store = FileSnapshotTargetStore(Path.of(cfg.targetsFile))
    CdnMenu.run(
        store = store,
        catalog = EmbeddedMirrorCatalog(),
        envFile = Path.of(cfg.envFile),
        snapshotDir = dir.toString(),
    )
}

private fun runStatus()
{
    val cfg = CdnBootstrap.load()
    val dir = ensureSnapshotDir(cfg) ?: return
    val store = FileSnapshotTargetStore(Path.of(cfg.targetsFile))
    CdnMenu.printStatus(
        store = store,
        statusReader = DiskMirrorStatusReader(dir),
    )
}

private fun runSync()
{
    val cfg = CdnBootstrap.load()
    val dir = ensureSnapshotDir(cfg) ?: kotlin.system.exitProcess(1)
    val log = LoggerFactory.getLogger("rpcnode-cdn")
    val targets = FileSnapshotTargetStore(Path.of(cfg.targetsFile))
    if (targets.list().isEmpty())
    {
        log.warn(
            "no targets in {} — run: java -jar rpcnode-cdn.jar menu",
            cfg.targetsFile,
        )
    }
    log.info(
        "rpcnode-cdn {} dir={}/snapshots targets={} jobs={} poll={}s origin={}",
        cfg.version,
        dir,
        cfg.targetsFile,
        cfg.downloadJobs,
        cfg.pollSec,
        cfg.publicOrigin ?: "(unset)",
    )
    val source = LocalSnapshotSource(
        targets = targets,
        catalog = EmbeddedMirrorCatalog(),
    )
    val store = DiskSnapshotMirrorStore(dir)
    runBlocking {
        supervisorScope {
            WatchSnapshotsUseCase(
                source = source,
                store = store,
                scope = this,
                jobs = cfg.downloadJobs,
                publicOrigin = cfg.publicOrigin,
            ).run(cfg.pollSec)
        }
    }
}

/**
 * Do not start without SNAPSHOT_CDN_DIR.
 * On a TTY: force disk/folder pick (default = cwd). Without TTY (systemd): fail.
 */
private fun ensureSnapshotDir(cfg: CdnConfig): Path?
{
    val raw = cfg.snapshotDir?.trim()?.ifEmpty { null }
    if (raw != null)
    {
        when (val result = CdnSnapshotDirPicker.ensureWritable(Path.of(raw)))
        {
            is CdnSnapshotDirPicker.EnsureResult.Ok -> return result.path
            is CdnSnapshotDirPicker.EnsureResult.Err ->
            {
                System.err.println("ERROR: SNAPSHOT_CDN_DIR invalid (${result.message})")
                // Continue to interactive pick when possible.
            }
        }
    }

    val terminal = CdnTerminal.create()
    if (!terminal.terminalInfo.inputInteractive)
    {
        val msg =
            "SNAPSHOT_CDN_DIR is not set — cannot start sync. " +
                "Run once in a terminal to pick a disk/folder " +
                "(default: current directory), or: java -jar rpcnode-cdn.jar menu"
        LoggerFactory.getLogger("rpcnode-cdn").error(msg)
        System.err.println("ERROR: $msg")
        return null
    }

    System.err.println("SNAPSHOT_CDN_DIR is not set — pick a disk and folder before continuing.")
    val chosen = CdnSnapshotDirPicker.pick(
        terminal = terminal,
        current = CdnSnapshotDirPicker.launchCwd(),
    ) ?: return null
    CdnSnapshotDirPicker.saveToEnvFile(
        envFile = Path.of(cfg.envFile),
        snapshotDir = chosen,
        pollSec = cfg.pollSec,
        downloadJobs = cfg.downloadJobs,
        targetsFile = Path.of(cfg.targetsFile),
    )
    return chosen
}
