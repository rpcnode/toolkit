package rpcnode.toolkit.clients

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import rpcnode.toolkit.clients.application.ArtifactDownloader

class FakeArtifactDownloader(
    private val fail: Boolean = false,
) : ArtifactDownloader
{
    val urls: MutableList<String> = CopyOnWriteArrayList()
    val inFlight = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)

    override suspend fun download(url: String, dest: java.nio.file.Path, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit)
    {
        urls += url
        val now = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, now) }
        if (fail)
        {
            inFlight.decrementAndGet()
            throw IllegalStateException("fake download failure for $url")
        }
        onProgress(0, 10)
        Files.createDirectories(dest.parent)
        Files.writeString(dest, "fake")
        onProgress(10, 10)
        inFlight.decrementAndGet()
    }
}
