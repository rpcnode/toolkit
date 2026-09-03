package rpcnode.toolkit.chains.tron.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/** java-tron FullNode: `java -jar FullNode.jar -c <config>`. JDK major comes from clients YAML. */
class TronNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.TRON

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val jar = ctx.program.trim().ifBlank { "FullNode.jar" }
        val conf = ctx.configFile?.trim()?.takeIf { it.isNotEmpty() } ?: "config.conf"
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "java_jar",
                entry = jar,
                // WorkingDirectory is already node_dir; without -d java-tron creates ./output-directory
                // and treats that as the data root (ignoring the disk-layout path).
                args = listOf("-c", conf, "-d", "."),
                javaMajor = ctx.javaMajor,
                logFile = ctx.logFile,
            ),
            height = NodeHeightSpec(
                kind = "tron_http",
                portRole = "http_fullnode",
            ),
        )
    }
}
