package rpcnode.toolkit.auth.domain.model

object PanelPassword
{
    const val MIN_LENGTH = 8

    fun isLongEnough(password: String): Boolean = password.length >= MIN_LENGTH
}
