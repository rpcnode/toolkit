package rpcnode.toolkit.agent.domain

import java.nio.file.Path

/** User-writable dirs for this agent on the current OS (XDG / Known Folders / Standard Directories). */
interface AgentDirectories
{
    fun configDir(): Path
    fun cacheDir(): Path
    fun logDir(): Path
}
