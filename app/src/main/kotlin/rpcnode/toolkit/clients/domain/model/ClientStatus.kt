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
                return if (latestVersion.isNotBlank() && isNewerClientRelease(latestVersion, currentVersion)) STALE else PIN
            }
            if (currentVersion.isBlank())
            {
                return MISSING
            }
            if (latestVersion.isNotBlank() && isNewerClientRelease(latestVersion, currentVersion))
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

/**
 * True when [latest] is a newer release than the installed [current] — not merely a different string.
 * Numbers are compared part by part (`1.10.0` > `1.9.9`, `4.8.2.3-pq1-build2` > `…-build1`, missing parts are 0) and a
 * pre-release (`-alpha`, `-beta`, `-rc`, `-preview`) is older than the same number without the tag: installed
 * `1.8.0-alpha` is ahead of latest `1.7.8`, but `1.8.0` is newer than `1.8.0-alpha`. Strings without digits
 * (`nightly`) cannot be ordered, so any difference counts as newer.
 */
fun isNewerClientRelease(latest: String, current: String): Boolean
{
    if (sameVersion(latest, current))
    {
        return false
    }
    val order = compareClientVersions(latest, current) ?: return true
    return order > 0
}

/** <0 / 0 / >0 like [Comparable]; null when either side has no number to order by. See [isNewerClientRelease]. */
fun compareClientVersions(a: String, b: String): Int?
{
    val x = parseClientVersion(a) ?: return null
    val y = parseClientVersion(b) ?: return null
    for (i in 0 until maxOf(x.numbers.size, y.numbers.size))
    {
        val c = x.numbers.getOrElse(i) { 0L }.compareTo(y.numbers.getOrElse(i) { 0L })
        if (c != 0)
        {
            return c
        }
    }
    // same numbers: a release beats any pre-release; two pre-releases order by kind (alpha < beta < pre < rc), then number
    val px = x.pre
    val py = y.pre
    return when
    {
        px == null && py == null -> 0
        px == null -> 1
        py == null -> -1
        px.first != py.first -> px.first.compareTo(py.first)
        else -> px.second.compareTo(py.second)
    }
}

private class ParsedClientVersion(val numbers: List<Long>, val pre: Pair<Int, Long>?)

private val PRE_RELEASE = Regex("(?i)(?:^|[-._+/])(alpha|beta|preview|pre|rc)(?:[-._]?(\\d+))?(?![a-z])")

private fun parseClientVersion(raw: String): ParsedClientVersion?
{
    val pre = PRE_RELEASE.find(raw)
    val head = if (pre != null) raw.substring(0, pre.range.first) else raw
    val numbers = Regex("\\d+").findAll(head).mapNotNull { it.value.toLongOrNull() }.toList()
    if (numbers.isEmpty())
    {
        return null
    }
    val kind = when (pre?.groupValues?.get(1)?.lowercase())
    {
        null -> return ParsedClientVersion(numbers, null)
        "alpha" -> 0
        "beta" -> 1
        "pre", "preview" -> 2
        else -> 3
    }
    return ParsedClientVersion(numbers, kind to (pre.groupValues[2].toLongOrNull() ?: 0L))
}

/** Loose match ignoring a leading `v`/`V`. */
internal fun sameVersion(a: String, b: String): Boolean
{
    fun norm(s: String) = s.trim().removePrefix("v").removePrefix("V").trim()
    val x = norm(a)
    val y = norm(b)
    return x.isNotEmpty() && x == y
}
