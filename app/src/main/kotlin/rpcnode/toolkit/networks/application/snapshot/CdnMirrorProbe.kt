package rpcnode.toolkit.networks.application.snapshot

/** Probe CDN VERSION text and archive presence (HEAD). */
interface CdnMirrorProbe
{
    suspend fun versionText(url: String): String?
    suspend fun archivePresent(url: String): Boolean
}
