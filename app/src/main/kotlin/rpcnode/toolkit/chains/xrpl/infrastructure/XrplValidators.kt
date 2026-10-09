package rpcnode.toolkit.chains.xrpl.infrastructure

/**
 * Canonical UNL sites/keys for stock (non-validator) xrpld.
 * Do not emit `[validator_list_threshold] 0` — xrpld treats a stray "0" as a publisher key.
 */
object XrplValidators
{
    private const val VL_KEY_RIPPLE = "ED2677ABFFD1B33AC6FBC3062B71F1E8397C1505E1C42C64D11AD1B28FF73F4734"
    private const val VL_KEY_XRPLF = "ED42AEC58B701EEBB77356FFFEC26F83C1F0407263530F068C7C73D392C7E06FD1"
    private const val VL_KEY_ALTNET = "ED264807102805220DA0F312E71FC2C69E1552C9C5790F6C25E3729DEB573D5860"

    fun body(env: String): String
    {
        return if (XrplClusters.normalizeEnv(env) == "testnet")
        {
            """
            |[validator_list_sites]
            |https://vl.altnet.rippletest.net
            |
            |[validator_list_keys]
            |$VL_KEY_ALTNET
            |""".trimMargin()
        }
        else
        {
            """
            |[validator_list_sites]
            |https://vl.ripple.com
            |https://unl.xrplf.org
            |
            |[validator_list_keys]
            |$VL_KEY_RIPPLE
            |$VL_KEY_XRPLF
            |""".trimMargin()
        }
    }
}
