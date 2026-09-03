package rpcnode.toolkit.agent.domain.model

data class AgentIdentity(
    val version: String,
    val os: String,
    val arch: String,
    val port: Int,
)
{
    val osPretty: String
        get() = "$os/$arch"
}
