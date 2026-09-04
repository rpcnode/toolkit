package rpcnode.toolkit.settings.application.get

fun interface InstallStampReader
{
    fun read(): InstallStamp?
}

fun interface InstallStampWriter
{
    fun write(stamp: InstallStamp)
}

data class InstallStamp(
    val version: String,
    val installedAt: String,
    val updatedAt: String,
)
