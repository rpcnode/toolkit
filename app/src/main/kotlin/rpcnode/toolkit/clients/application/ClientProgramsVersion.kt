package rpcnode.toolkit.clients.application

import rpcnode.toolkit.clients.domain.model.ClientVersionPin

/**
 * Multi-program client version strings (ethereum geth + lighthouse).
 * Format: `geth 1.17.5 · lighthouse 8.2.2`. Single-program stays a bare version.
 */
fun formatClientProgramsVersion(programs: Map<String, String>): String
{
    val entries = programs.entries
        .map { it.key.trim() to it.value.trim() }
        .filter { (id, ver) -> id.isNotEmpty() && ver.isNotEmpty() }
    if (entries.isEmpty())
    {
        return ""
    }
    if (entries.size == 1)
    {
        return entries.single().second
    }
    return orderPrograms(entries.map { it.first })
        .mapNotNull { id ->
            val ver = entries.firstOrNull { it.first.equals(id, ignoreCase = true) }?.second ?: return@mapNotNull null
            "$id $ver"
        }
        .joinToString(" · ")
}

/** Prefer execution-primary (geth), then alphabetical. */
fun orderPrograms(programIds: Collection<String>): List<String>
{
    val ids = programIds.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    return ids.sortedWith(
        compareBy(
            { if (it.equals("geth", ignoreCase = true)) 0 else 1 },
            { it.lowercase() },
        ),
    )
}

fun formatPinsVersion(
    pins: List<ClientVersionPin>,
    preferLatest: Boolean,
): String
{
    val map = linkedMapOf<String, String>()
    for (pin in pins)
    {
        val program = pin.program.trim()
        if (program.isEmpty())
        {
            continue
        }
        val ver = if (preferLatest)
        {
            pin.latestVersion.trim().ifEmpty { pin.currentVersion.trim() }
        }
        else
        {
            pin.currentVersion.trim().ifEmpty { pin.latestVersion.trim() }
        }
        if (ver.isNotEmpty())
        {
            map[program] = ver
        }
    }
    return formatClientProgramsVersion(map)
}

/**
 * Parses `geth 1.17.5 · lighthouse 8.2.2` into program → version.
 * Bare single tokens (`1.17.5`, `GreatVoyage-v4.8.2`) yield an empty map.
 */
fun parseClientProgramsVersion(raw: String): Map<String, String>
{
    val trimmed = raw.trim()
    if (trimmed.isEmpty())
    {
        return emptyMap()
    }
    val parts = if (trimmed.contains('·'))
    {
        trimmed.split('·')
    }
    else
    {
        listOf(trimmed)
    }
    val out = linkedMapOf<String, String>()
    for (part in parts)
    {
        val fields = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size < 2)
        {
            continue
        }
        val name = fields[0]
        val ver = fields.drop(1).joinToString(" ")
        if (!looksLikeProgramId(name) || ver.isEmpty())
        {
            continue
        }
        out[name.lowercase()] = ver
    }
    return out
}

/** Version token for [program] inside a dual string; empty when that program is absent. */
fun namedClientVersion(raw: String, program: String): String
{
    val name = program.trim().lowercase()
    if (name.isEmpty())
    {
        return ""
    }
    val parsed = parseClientProgramsVersion(raw)
    if (parsed.isNotEmpty())
    {
        return parsed[name].orEmpty()
    }
    val s = raw.trim()
    if (s.isEmpty())
    {
        return ""
    }
    val fields = s.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (fields.isNotEmpty() && fields[0] == name)
    {
        return fields.drop(1).joinToString(" ").ifEmpty { s }
    }
    return ""
}

/**
 * True when [local] is behind [latest] for any named program, or when both are bare
 * and differ (loose `v` prefix). Never compares geth's version to lighthouse's.
 */
fun clientProgramsUpdateAvailable(local: String, latest: String): Boolean
{
    val localParts = parseClientProgramsVersion(local)
    val latestParts = parseClientProgramsVersion(latest)
    if (localParts.isNotEmpty() || latestParts.isNotEmpty())
    {
        val names = (localParts.keys + latestParts.keys).toSet()
        return names.any { name ->
            val left = localParts[name].orEmpty().ifEmpty { namedClientVersion(local, name) }
            val right = latestParts[name].orEmpty().ifEmpty { namedClientVersion(latest, name) }
            left.isNotEmpty() && right.isNotEmpty() && !looseVersionEqual(left, right)
        }
    }
    val a = local.trim()
    val b = latest.trim()
    return a.isNotEmpty() && b.isNotEmpty() && !looseVersionEqual(a, b)
}

/**
 * Multi-pin outdated check against a node VERSION string.
 * Legacy bare VERSION is matched to the pin whose current/latest equals it; otherwise
 * only the [primary] program (or first pin) is compared.
 */
fun pinsUpdateAvailable(
    localRaw: String,
    pins: List<ClientVersionPin>,
    primary: String = "",
): Boolean
{
    if (pins.isEmpty())
    {
        return false
    }
    val local = localRaw.trim()
    if (local.isEmpty())
    {
        return false
    }
    val parsed = parseClientProgramsVersion(local)
    return pins.any { pin ->
        val latest = pin.latestVersion.trim().ifEmpty { pin.currentVersion.trim() }
        if (latest.isEmpty())
        {
            return@any false
        }
        val left = when
        {
            parsed.isNotEmpty() -> namedClientVersion(local, pin.program)
            looseVersionEqual(local, pin.currentVersion) || looseVersionEqual(local, pin.latestVersion) -> local
            pin.program.equals(primary, ignoreCase = true) ||
                (primary.isBlank() && pin.program.equals(pins.first().program, ignoreCase = true)) ->
            {
                val matchesSibling = pins.any { other ->
                    !other.program.equals(pin.program, ignoreCase = true) &&
                        (looseVersionEqual(local, other.currentVersion) ||
                            looseVersionEqual(local, other.latestVersion))
                }
                if (matchesSibling) "" else local
            }
            else -> ""
        }
        left.isNotEmpty() && !looseVersionEqual(left, latest)
    }
}

private fun looksLikeProgramId(name: String): Boolean
{
    val n = name.trim()
    if (n.isEmpty())
    {
        return false
    }
    // Version-like tokens are not program ids.
    if (n.first().isDigit() || n.startsWith("v", ignoreCase = true) && n.drop(1).firstOrNull()?.isDigit() == true)
    {
        return false
    }
    return n.any { it.isLetter() }
}

private fun looseVersionEqual(a: String, b: String): Boolean
{
    fun norm(s: String) = s.trim().removePrefix("v").removePrefix("V").trim()
    val x = norm(a)
    val y = norm(b)
    return x.isNotEmpty() && x == y
}
