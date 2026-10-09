package rpcnode.toolkit.clients.domain.model

/** Derived status of one [ClientVersionPin] — never stored as a source of truth, always computed. */
enum class ClientStatus(val value: String)
{
    OK("ok"),
    STALE("stale"),
    FAIL("fail"),
    PIN("pin"),
    WAIT("wait"),
    MISSING("missing"),
    ;

    companion object
    {
        fun parse(raw: String): ClientStatus? = entries.firstOrNull { it.value == raw.trim().lowercase() }

        fun compute(
            currentVersion: String,
            latestVersion: String,
            skipReason: String,
            probeError: String,
        ): ClientStatus
        {
            if (probeError.isNotBlank())
            {
                return FAIL
            }
            if (skipReason.isNotBlank())
            {
                return if (latestVersion.isNotBlank() && !sameVersion(currentVersion, latestVersion)) STALE else PIN
            }
            if (currentVersion.isBlank())
            {
                return MISSING
            }
            if (latestVersion.isNotBlank() && !sameVersion(currentVersion, latestVersion))
            {
                return STALE
            }
            if (latestVersion.isBlank())
            {
                return WAIT
            }
            return OK
        }
    }
}

/** Loose match ignoring a leading `v`/`V`. */
internal fun sameVersion(a: String, b: String): Boolean
{
    fun norm(s: String) = s.trim().removePrefix("v").removePrefix("V").trim()
    val x = norm(a)
    val y = norm(b)
    return x.isNotEmpty() && x == y
}
