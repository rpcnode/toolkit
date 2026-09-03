package rpcnode.toolkit.chains.arb.infrastructure.docker

/**
 * Offchain Labs ships Nitro only as Docker Hub images (`offchainlabs/nitro-node`).
 * Release notes pin the canonical tag (`v3.11.3-beb2108`); Hub also publishes
 * `-slim` / `-validator` / `-amd64` / `-arm64` variants we ignore for full-node RPC.
 */
object ArbNitroDockerTags
{
    const val IMAGE = "offchainlabs/nitro-node"

    private val IMAGE_IN_BODY = Regex(
        """offchainlabs/nitro-node:(v[\w.\-]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Canonical full-node tag from a GitHub release body, or null. */
    fun fromReleaseBody(body: String): String?
    {
        val candidates = IMAGE_IN_BODY.findAll(body).map { it.groupValues[1].trim() }.toList()
        return candidates.firstOrNull { isCanonicalFullNodeTag(it) }
    }

    /**
     * Among Hub tag names for a GitHub release tag (e.g. `v3.11.3`), pick
     * `v3.11.3-<7hex>` without slim/validator/arch suffixes.
     */
    fun pickCanonical(hubTags: List<String>, gitTag: String): String?
    {
        val base = gitTag.trim()
        if (base.isEmpty())
        {
            return null
        }
        val re = Regex("^${Regex.escape(base)}-[a-fA-F0-9]+$")
        return hubTags.firstOrNull { re.matches(it) && isCanonicalFullNodeTag(it) }
    }

    fun imageRef(dockerTag: String): String = "$IMAGE:${dockerTag.trim()}"

    fun isCanonicalFullNodeTag(tag: String): Boolean
    {
        val t = tag.lowercase()
        if (t.isEmpty())
        {
            return false
        }
        if (t.contains("validator") || t.contains("slim") || t.contains("dev") ||
            t.contains("stripped")
        ) {
            return false
        }
        if (t.endsWith("-amd64") || t.endsWith("-arm64"))
        {
            return false
        }
        return t.startsWith("v")
    }
}
