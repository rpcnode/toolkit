package rpcnode.toolkit.agent.application.snapshot

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Downloads a small byte range from each URL on the host and reports throughput. */
class ProbeSnapshotSpeedUseCase(
    private val probe: SnapshotSpeedProbe,
)
{
    suspend operator fun invoke(samples: List<SnapshotSpeedSampleRequest>): List<SnapshotSpeedSampleResult> =
        coroutineScope {
            samples.map { sample ->
                async {
                    val reading = probe.probe(sample.url)
                    SnapshotSpeedSampleResult(
                        id = sample.id,
                        available = reading.available,
                        bytesPerSec = reading.bytesPerSec,
                        sampleBytes = reading.sampleBytes,
                        latencyMs = reading.latencyMs,
                        detail = reading.detail,
                    )
                }
            }.awaitAll()
        }
}
