package rpcnode.toolkit.clients.infrastructure.docker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import rpcnode.toolkit.clients.application.ClientBuildRequest
import rpcnode.toolkit.clients.application.ClientBuildResult
import rpcnode.toolkit.clients.application.ClientBuilder
import rpcnode.toolkit.clients.domain.model.ClientBuildSpec
import rpcnode.toolkit.shared.infrastructure.classpath.ClasspathChainResources

/**
 * Builds on the machine this runs on, with its own Docker daemon. The panel uses it only as the fallback when no
 * enrolled host agent can build; the Dockerfile ships beside `chains/<network>/clients.yml`.
 */
class DockerClientBuilder(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    private val docker: DockerBuildRunner = DockerBuildRunner(),
) : ClientBuilder
{
    override suspend fun build(request: ClientBuildRequest, onLog: (String) -> Unit): ClientBuildResult =
        runInterruptible(Dispatchers.IO) {
            val build = request.spec.build
                ?: return@runInterruptible ClientBuildResult.Failed("program has no build block")
            docker.problem()?.let { return@runInterruptible ClientBuildResult.Unavailable(it) }
            val dockerfile = readDockerfile(request, build)
                ?: return@runInterruptible ClientBuildResult.Failed(
                    "Dockerfile ${build.dockerfile} not found for ${request.spec.network.value}",
                )
            val names = mutableListOf<String>()
            for (platform in request.platforms ?: build.platforms)
            {
                when (
                    val one = docker.build(
                        dockerfile, platform, request.version.trim(), request.tag.trim(), build.outputs, request.outDir, onLog,
                    )
                )
                {
                    is ClientBuildResult.Built -> names += one.names
                    else -> return@runInterruptible one
                }
            }
            ClientBuildResult.Built(names)
        }

    private fun readDockerfile(request: ClientBuildRequest, build: ClientBuildSpec): String? =
        dockerfileText(classLoader, request.spec.network.value, build.dockerfile)

    companion object
    {
        fun dockerfileText(classLoader: ClassLoader, network: String, file: String): String? =
            ClasspathChainResources.open(classLoader, network, file)?.use { it.readBytes().decodeToString() }
    }
}
