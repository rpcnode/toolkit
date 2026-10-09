package rpcnode.toolkit.datadir.domain

const val PRODUCT_DATA_ROOT = "rpcnode"

/**
 * `<mount>/rpcnode/<parts…>`. Empty, `.`, `/`, or `/data` → `/data/rpcnode/…`.
 */
fun joinData(mount: String, vararg parts: String): String
{
    val cleaned = posixClean(mount.trim())
    val base = when (cleaned)
    {
        "", ".", "/", "/data" -> "/data/$PRODUCT_DATA_ROOT"
        else -> "$cleaned/$PRODUCT_DATA_ROOT"
    }
    if (parts.isEmpty())
    {
        return posixClean(base)
    }
    return posixClean(parts.fold(base) { acc, p -> "$acc/$p" })
}

internal fun posixClean(path: String): String
{
    if (path.isEmpty())
    {
        return "."
    }
    val abs = path.startsWith("/")
    val stack = ArrayDeque<String>()
    for (seg in path.split('/'))
    {
        when
        {
            seg.isEmpty() || seg == "." -> Unit
            seg == ".." ->
            {
                if (stack.isNotEmpty() && stack.last() != "..")
                {
                    stack.removeLast()
                }
                else if (!abs)
                {
                    stack.addLast("..")
                }
            }
            else -> stack.addLast(seg)
        }
    }
    val body = stack.joinToString("/")
    return when
    {
        abs && body.isEmpty() -> "/"
        abs -> "/$body"
        body.isEmpty() -> "."
        else -> body
    }
}
