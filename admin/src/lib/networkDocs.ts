/**
 * Official upstream fullnode / client docs per network (Config modal book icon).
 * Extend when adding a network — prefer project docs over blogs / RpcNode internals.
 */

/** Canonical official docs for running/operating the client we provision. */
const OFFICIAL_FULLNODE_DOCS: Record<string, string> = {
  bitcoin: 'https://bitcoin.org/en/full-node',
  doge: 'https://github.com/dogecoin/dogecoin/blob/master/doc/getting-started.md',
  ltc: 'https://github.com/litecoin-project/litecoin',
  dash: 'https://docs.dash.org/en/stable/docs/user/wallets/dashcore/installation-linux.html',
  bch: 'https://docs.bitcoincashnode.org/',
  tron: 'https://developers.tron.network/docs/deploy-the-fullnode-or-supernode',
  ethereum: 'https://geth.ethereum.org/docs/getting-started',
  polygon: 'https://docs.polygon.technology/pos/how-to/full-node/full-node-binaries/',
  bsc: 'https://docs.bnbchain.org/bnb-smart-chain/developers/node_operators/full_node/',
  etc: 'https://etclabscore.github.io/core-geth/getting-started/installation/',
  solana: 'https://docs.anza.xyz/operations/setup-an-rpc-node',
  xrpl: 'https://xrpl.org/docs/infrastructure/configuration/server-modes/run-xrpld-as-a-stock-server',
  cardano: 'https://developers.cardano.org/docs/get-started/infrastructure/node/installing-cardano-node/',
  stellar: 'https://developers.stellar.org/docs/data/apis/rpc/admin-guide',
  ton: 'https://docs.ton.org/nodes/cpp/setup-mytonctrl',
  arb: 'https://docs.arbitrum.io/run-arbitrum-node/run-full-node',
  optimism: 'https://docs.optimism.io/operators/node-operators/overview',
  base: 'https://docs.base.org/chain/run-a-base-node',
  hyperliquid: 'https://github.com/hyperliquid-dex/node',
  robinhood: 'https://docs.robinhood.com/chain/run-a-full-node/',
  zcash: 'https://zebra.zfnd.org/',
  sui: 'https://docs.sui.io/operators/full-node/sui-full-node',
  aptos: 'https://aptos.dev/network/nodes/full-node/deployments/using-source-code',
  avalanche: 'https://build.avax.network/docs/nodes/run-a-node/using-binary',
}

/** Aliases → catalog network id. */
const NETWORK_ALIASES: Record<string, string> = {
  btc: 'bitcoin',
  eth: 'ethereum',
  matic: 'polygon',
  arbitrum: 'arb',
  op: 'optimism',
  xrp: 'xrpl',
  ripple: 'xrpl',
  dogecoin: 'doge',
  litecoin: 'ltc',
  bitcoincash: 'bch',
  zec: 'zcash',
  avax: 'avalanche',
}

/** RpcNode product home when network has no mapped official docs. */
export const RPCNODE_DOCS_FALLBACK = 'https://rpcnode.dev'

/**
 * Official fullnode/client documentation URL for a network id.
 * Unknown networks → RpcNode home (not internal `/docs/developer-api.md`).
 */
export function officialDocsUrl(network?: string | null): string {
  const raw = (network || '').toLowerCase().trim()
  if (!raw) return RPCNODE_DOCS_FALLBACK
  const id = NETWORK_ALIASES[raw] || raw
  return OFFICIAL_FULLNODE_DOCS[id] || RPCNODE_DOCS_FALLBACK
}
