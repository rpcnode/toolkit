package rpcnode.toolkit.clients.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.clients.application.InstallPlan
import rpcnode.toolkit.clients.application.InstallPlanWriter

class FileInstallPlanWriter : InstallPlanWriter
{
    private val yaml = Yaml(
        DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        },
    )

    override suspend fun write(dir: Path, plan: InstallPlan) = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val root = linkedMapOf<String, Any?>(
            "version" to plan.version,
            "network" to plan.network,
            "env" to plan.env,
            "program" to plan.program,
            "files" to plan.files.map { f ->
                val m = linkedMapOf<String, Any?>("role" to f.role, "path" to f.path)
                if (!f.arch.isNullOrBlank())
                {
                    m["arch"] = f.arch
                }
                m
            },
        )
        plan.launch?.let { launch ->
            root["launch"] = linkedMapOf(
                "kind" to launch.kind,
                "entry" to launch.entry,
            )
        }
        Files.writeString(dir.resolve("install-plan.yml"), yaml.dump(root))
        Unit
    }
}
