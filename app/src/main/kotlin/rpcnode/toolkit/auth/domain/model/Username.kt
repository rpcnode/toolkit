package rpcnode.toolkit.auth.domain.model

@JvmInline
value class Username private constructor(val value: String)
{
    companion object
    {
        val ADMIN = Username("admin")

        fun parse(raw: String): Username?
        {
            val n = raw.trim()
            if (n.isEmpty() || n.contains(':'))
            {
                return null
            }
            return Username(n)
        }

        /** First-run setup: blank → admin. Colon is still invalid. */
        fun parseOrAdmin(raw: String): Username?
        {
            val n = raw.trim()
            if (n.isEmpty())
            {
                return ADMIN
            }
            return parse(n)
        }
    }
}
