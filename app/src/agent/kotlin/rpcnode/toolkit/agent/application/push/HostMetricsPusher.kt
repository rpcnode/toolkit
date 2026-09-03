package rpcnode.toolkit.agent.application.push

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class HostMetricsPusher(
    private val push: PushHostMetricsUseCase,
    private val scope: CoroutineScope,
    private val interval: Duration = 5.seconds,
)
{
    private val log = LoggerFactory.getLogger(HostMetricsPusher::class.java)
    private var started = false

    fun start()
    {
        if (started)
        {
            return
        }
        started = true
        scope.launch {
            while (isActive)
            {
                try
                {
                    push()
                }
                catch (e: Exception)
                {
                    log.warn("metrics push: {}", e.message)
                }
                delay(interval)
            }
        }
    }
}
