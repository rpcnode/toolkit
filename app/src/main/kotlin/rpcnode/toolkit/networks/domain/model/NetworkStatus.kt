package rpcnode.toolkit.networks.domain.model

enum class NetworkStatus(val value: String)
{
    PENDING("pending"),
    READY("ready"),
    SKIPPED("skipped"),
    ;

    companion object
    {
        fun parse(raw: String): NetworkStatus? = entries.firstOrNull { it.value == raw.trim().lowercase() }
    }
}
