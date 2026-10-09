package rpcnode.toolkit.chains.bitcoin.infrastructure.proc

import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter

/** Bitcoin Core host start — shared bitcoind-style launcher. */
class BitcoinNodeProcessStarter : HostNodeProcessStarter by BitcoreNodeProcessStarter()
