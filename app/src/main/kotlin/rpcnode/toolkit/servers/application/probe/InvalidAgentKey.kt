package rpcnode.toolkit.servers.application.probe

/** Panel → host agent rejected Bearer / X-Api-Token. */
object InvalidAgentKey
{
    const val ERROR = "invalid_agent_key"
    const val MESSAGE =
        "Invalid agent token — the host agent rejected the key. Update the server agent key to match the token on the host."
}
