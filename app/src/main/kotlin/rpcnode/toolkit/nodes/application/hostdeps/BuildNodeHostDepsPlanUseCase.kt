package rpcnode.toolkit.nodes.application.hostdeps

import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

class BuildNodeHostDepsPlanUseCase(
    private val nodes: NodeRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
)
{
    suspend operator fun invoke(idRaw: String): GetNodeHostDepsPlanResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeHostDepsPlanResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeHostDepsPlanResult.NotFound
        val networkFacts = facts.factsFor(node.network)
        val packages = linkedMapOf<String, NodeHostDepPlanItem>()
        fun addPackage(name: String)
        {
            val pkg = name.trim()
            if (pkg.isEmpty() || packages.containsKey(pkg))
            {
                return
            }
            // `clang-21` in hostPackages = that LLVM major as the default `clang` (not a plain distro package)
            val clangMajor = CLANG_MAJOR.matchEntire(pkg)?.groupValues?.get(1)
            packages[pkg] = NodeHostDepPlanItem(
                id = pkg,
                kind = if (clangMajor != null) "clang" else "package",
                name = pkg,
                label = if (clangMajor != null) "clang $clangMajor (LLVM)" else pkg,
            )
        }
        for (pkg in COMMON_HOST_PACKAGES)
        {
            addPackage(pkg)
        }
        for (pkg in networkFacts?.hostPackages.orEmpty())
        {
            addPackage(pkg)
        }
        val program = networkFacts?.clientConfig?.program?.trim().orEmpty()
        if (program.isNotEmpty())
        {
            val javaMajor = catalog.programsFor(node.network, node.env)
                .firstOrNull { it.programId.equals(program, ignoreCase = true) }
                ?.requirements
                ?.javaMajor
            if (javaMajor != null && javaMajor > 0)
            {
                packages["java-$javaMajor"] = NodeHostDepPlanItem(
                    id = "java-$javaMajor",
                    kind = "java",
                    name = "java",
                    javaMajor = javaMajor,
                    label = "Java $javaMajor",
                )
            }
        }
        return GetNodeHostDepsPlanResult.Ok(
            NodeHostDepsPlan(nodeId = node.id.value, deps = packages.values.toList()),
        )
    }
}

private val CLANG_MAJOR = Regex("""clang-(\d{1,3})""")
