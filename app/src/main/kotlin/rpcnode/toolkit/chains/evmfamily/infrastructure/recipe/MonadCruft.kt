package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import java.nio.file.Path

/**
 * The vendor `monad-cruft` job (hourly `clear-old-artifacts.sh`) with every path under node_dir. Without it the consensus
 * ledger (`ledger/headers`, `ledger/bodies`) and the dated forkpoint / validators copies grow until the state disk is full.
 * Retention defaults are the vendor's (docs: ledger 10 h, forkpoint 5 h, validators 30 d). Nothing is deleted while the node
 * has written no ledger file for 20 minutes: a stalled node keeps its evidence.
 */
object MonadCruft
{
    const val SCRIPT = "bin/rpcnode-monad-cruft.sh"
    const val RETENTION_LEDGER_MIN = 600
    const val RETENTION_FORKPOINT_MIN = 300
    const val RETENTION_VALIDATORS_MIN = 43200

    fun script(state: Path): String
    {
        val dir = "'" + state.toString().replace("'", "'\\''") + "'"
        return """
            #!/bin/bash
            # managed by RpcNode — hourly clean-up of Monad consensus artifacts (vendor monad-cruft, paths under node_dir)
            STATE=$dir
            RETENTION_FORKPOINT=${'$'}{RETENTION_FORKPOINT:-$RETENTION_FORKPOINT_MIN}
            RETENTION_VALIDATORS=${'$'}{RETENTION_VALIDATORS:-$RETENTION_VALIDATORS_MIN}
            RETENTION_LEDGER=${'$'}{RETENTION_LEDGER:-$RETENTION_LEDGER_MIN}
            NEW=${'$'}(find "${'$'}STATE/ledger" -type f -mmin -20 2>/dev/null | head -n 1)
            if [ -z "${'$'}NEW" ]; then
              echo "no new ledger files in the last 20 minutes: nothing deleted"
              exit 0
            fi
            find "${'$'}STATE/config/forkpoint" -type f \( -name 'forkpoint.rlp.*' -o -name 'forkpoint.toml.*' \) -mmin "+${'$'}RETENTION_FORKPOINT" -delete 2>/dev/null
            find "${'$'}STATE/config/validators" -type f -name 'validators.toml.*' -mmin "+${'$'}RETENTION_VALIDATORS" -delete 2>/dev/null
            find "${'$'}STATE/ledger/headers" -type f -mmin "+${'$'}RETENTION_LEDGER" -delete 2>/dev/null
            find "${'$'}STATE/ledger/bodies" -type f -mmin "+${'$'}RETENTION_LEDGER" -delete 2>/dev/null
            exit 0
        """.trimIndent() + "\n"
    }
}
