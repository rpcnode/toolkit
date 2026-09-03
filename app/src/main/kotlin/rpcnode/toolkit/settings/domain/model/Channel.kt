package rpcnode.toolkit.settings.domain.model

data class Channel(
    val installOrigin: String,
    val clientsBaseUrl: String,
    val installBaseUrl: String,
    val agentDownloadUrl: String,
)
{
    val configured: Boolean get() = installOrigin.isNotEmpty()

    companion object
    {
        val EMPTY = Channel(
            installOrigin = "",
            clientsBaseUrl = "",
            installBaseUrl = "",
            agentDownloadUrl = "",
        )

        fun from(origin: InstallOrigin): Channel
        {
            val o = origin.value
            return Channel(
                installOrigin = o,
                clientsBaseUrl = o,
                installBaseUrl = "$o/install",
                agentDownloadUrl = "$o/install/binaries/rpcnode-agent.jar",
            )
        }
    }
}
