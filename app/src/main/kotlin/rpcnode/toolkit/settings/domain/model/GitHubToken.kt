package rpcnode.toolkit.settings.domain.model

@JvmInline
value class GitHubToken private constructor(val value: String)
{
    val masked: String
        get()
        {
            if (value.length < 8)
            {
                return "••••"
            }
            return value.take(4) + "…" + value.takeLast(4)
        }

    companion object
    {
        const val CREATE_URL = "https://github.com/settings/tokens/new"

        fun parse(raw: String): GitHubToken?
        {
            val t = raw.trim()
            if (t.isEmpty())
            {
                return null
            }
            return GitHubToken(t)
        }
    }
}

sealed interface StoredGitHubToken
{
    data object Absent : StoredGitHubToken
    data object Corrupt : StoredGitHubToken
    data class Present(val token: GitHubToken) : StoredGitHubToken
}
