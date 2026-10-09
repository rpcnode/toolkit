package rpcnode.toolkit.settings.application.get

fun interface InstallFiles
{
    fun exists(relative: String): Boolean
}
