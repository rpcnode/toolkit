# Central port plan — DO NOT pick ports outside your network's block

Every new network owns 30 consecutive ports starting at its BASE: three env blocks of 10 (env 0 = mainnet/production, env 1 = first testnet, env 2 = second testnet if any).
Within an env block (offset from the block start):
`+0` http/rpc · `+1` ws · `+2` p2p (tcp, udp too if needed) · `+3` engine/auth rpc (127.0.0.1) · `+4` metrics · `+5` consensus/cl rpc or rest/api · `+6` cl p2p · `+7` grpc / cl metrics / other · `+8`,`+9` spare.
Use only the offsets the client needs; never use a client's upstream default port (they clash with other networks). If a client opens implicit extra ports (QUIC = p2p+1 etc.) use the spares and note it.

| id | base | env0 block | env1 block | env2 block |
|---|---|---|---|---|
| abstract | 41000 | 41000-41009 | 41010-41019 | 41020-41029 |
| aleo | 41030 | 41030-41039 | 41040-41049 | 41050-41059 |
| aptos | 41060 | 41060-41069 | 41070-41079 | 41080-41089 |
| ault | 41090 | 41090-41099 | 41100-41109 | 41110-41119 |
| b3 | 41120 | 41120-41129 | 41130-41139 | 41140-41149 |
| berachain | 41150 | 41150-41159 | 41160-41169 | 41170-41179 |
| blast | 41180 | 41180-41189 | 41190-41199 | 41200-41209 |
| cardano | 41210 | 41210-41219 | 41220-41229 | 41230-41239 |
| celestia | 41240 | 41240-41249 | 41250-41259 | 41260-41269 |
| celo | 41270 | 41270-41279 | 41280-41289 | 41290-41299 |
| cosmos | 41300 | 41300-41309 | 41310-41319 | 41320-41329 |
| cyber | 41330 | 41330-41339 | 41340-41349 | 41350-41359 |
| data | 41360 | 41360-41369 | 41370-41379 | 41380-41389 |
| etc | 41390 | 41390-41399 | 41400-41409 | 41410-41419 |
| fantom | 41420 | 41420-41429 | 41430-41439 | 41440-41449 |
| flare | 41450 | 41450-41459 | 41460-41469 | 41470-41479 |
| fluent | 41480 | 41480-41489 | 41490-41499 | 41500-41509 |
| fraxtal | 41510 | 41510-41519 | 41520-41529 | 41530-41539 |
| fuel | 41540 | 41540-41549 | 41550-41559 | 41560-41569 |
| gnosis | 41570 | 41570-41579 | 41580-41589 | 41590-41599 |
| gravity | 41600 | 41600-41609 | 41610-41619 | 41620-41629 |
| hedera | 41630 | 41630-41639 | 41640-41649 | 41650-41659 |
| hemi | 41660 | 41660-41669 | 41670-41679 | 41680-41689 |
| immutable | 41690 | 41690-41699 | 41700-41709 | 41710-41719 |
| injective | 41720 | 41720-41729 | 41730-41739 | 41740-41749 |
| ink | 41750 | 41750-41759 | 41760-41769 | 41770-41779 |
| joc | 41780 | 41780-41789 | 41790-41799 | 41800-41809 |
| kaia | 41810 | 41810-41819 | 41820-41829 | 41830-41839 |
| lens | 41840 | 41840-41849 | 41850-41859 | 41860-41869 |
| lisk | 41870 | 41870-41879 | 41880-41889 | 41890-41899 |
| mantle | 41900 | 41900-41909 | 41910-41919 | 41920-41929 |
| megaeth | 41930 | 41930-41939 | 41940-41949 | 41950-41959 |
| moca | 41960 | 41960-41969 | 41970-41979 | 41980-41989 |
| mode | 41990 | 41990-41999 | 42000-42009 | 42010-42019 |
| monad | 42020 | 42020-42029 | 42030-42039 | 42040-42049 |
| morph | 42050 | 42050-42059 | 42060-42069 | 42070-42079 |
| near | 42080 | 42080-42089 | 42090-42099 | 42100-42109 |
| osmosis | 42110 | 42110-42119 | 42120-42129 | 42130-42139 |
| peaq | 42140 | 42140-42149 | 42150-42159 | 42160-42169 |
| plasma | 42170 | 42170-42179 | 42180-42189 | 42190-42199 |
| polkadot | 42200 | 42200-42209 | 42210-42219 | 42220-42229 |
| polygonzkevm | 42230 | 42230-42239 | 42240-42249 | 42250-42259 |
| redstone | 42260 | 42260-42269 | 42270-42279 | 42280-42289 |
| sahara | 42290 | 42290-42299 | 42300-42309 | 42310-42319 |
| scroll | 42320 | 42320-42329 | 42330-42339 | 42340-42349 |
| sei | 42350 | 42350-42359 | 42360-42369 | 42370-42379 |
| shibarium | 42380 | 42380-42389 | 42390-42399 | 42400-42409 |
| soneium | 42410 | 42410-42419 | 42420-42429 | 42430-42439 |
| sonic | 42440 | 42440-42449 | 42450-42459 | 42460-42469 |
| sophon | 42470 | 42470-42479 | 42480-42489 | 42490-42499 |
| stacks | 42500 | 42500-42509 | 42510-42519 | 42520-42529 |
| starknet | 42530 | 42530-42539 | 42540-42549 | 42550-42559 |
| stellar | 42560 | 42560-42569 | 42570-42579 | 42580-42589 |
| tempo | 42590 | 42590-42599 | 42600-42609 | 42610-42619 |
| unichain | 42620 | 42620-42629 | 42630-42639 | 42640-42649 |
| vana | 42650 | 42650-42659 | 42660-42669 | 42670-42679 |
| worldchain | 42680 | 42680-42689 | 42690-42699 | 42700-42709 |
| xai | 42710 | 42710-42719 | 42720-42729 | 42730-42739 |
| xlayer | 42740 | 42740-42749 | 42750-42759 | 42760-42769 |
| xrplevm | 42770 | 42770-42779 | 42780-42789 | 42790-42799 |
| zksync | 42800 | 42800-42809 | 42810-42819 | 42820-42829 |
| zora | 42830 | 42830-42839 | 42840-42849 | 42850-42859 |
| zerog | 42860 | 42860-42869 | 42870-42879 | 42880-42889 |

Already used by shipped networks: see app/src/main/resources/chains/*/clients.yml (nothing in 41000-42999 is used).

## aptos (allocated by the aptos spec, base 41060)
| env | http (REST) | p2p | metrics (inspection) | admin (127.0.0.1) | backup (127.0.0.1) |
|---|---|---|---|---|---|
| mainnet | 41060 | 41062 | 41064 | 41067 | 41068 |
| testnet | 41070 | 41072 | 41074 | 41077 | 41078 |
Implicit extras: none (aptos-node opens exactly these five listeners; indexer_grpc / tokio-console are off by default). +1,+3,+5,+6,+8,+9 spare.

## cardano (allocations used, from block 41210)
mainnet: p2p 41212, metrics 41214 · preprod: p2p 41222, metrics 41224 · preview: p2p 41232, metrics 41234. Optional indexer companions (install option `indexer`): +0 `ogmios` (41210/41220/41230, 127.0.0.1) and +7 `dbsync_metrics` (41217/41227/41237, db-sync's Prometheus; confined to loopback by its unit), both `config: none`. cardano-node has no HTTP/RPC port. The PostgreSQL port is the host server's own (found by the install script), not a catalog port.

## nonevm family (substrate / other non-EVM L1s) — allocated by specs/nonevm
| network | env | http/rpc (+0) | p2p (+2) | metrics (+4) | other |
|---|---|---|---|---|---|
| polkadot | mainnet (polkadot) | 42200 (HTTP+WS same port) | 42202 (tcp; litep2p also udp = same number) | 42204 | +1 reserved (ws is on +0), +3 spare, +5 REST API 42205, +6 history GraphQL 42206 (127.0.0.1), +7 history processor metrics 42207 (127.0.0.1) |
| polkadot | paseo | 42210 | 42212 | 42214 | +5 42215, +6 42216, +7 42217 |
| polkadot | westend | 42220 | 42222 | 42224 | +5 42225, +6 42226, +7 42227 |
| peaq | mainnet | 42140 (Substrate RPC + eth_* + WS) | 42142 | 42144 | +5 embedded relay RPC (127.0.0.1) 42145, +6 embedded relay p2p 42146 |
| peaq | agung | 42150 | 42152 | 42154 | +5 42155, +6 42156 |
| near | mainnet | 42080 (JSON-RPC + /status) | 42082 | 42084 (prometheus, 127.0.0.1) | |
| near | testnet | 42090 | 42092 | 42094 | |
| stacks | mainnet | 42500 (stacks-node RPC /v2/info) | 42502 | 42504 | |
| stacks | testnet | 42510 | 42512 | 42514 | |
| aleo | mainnet | 41030 (REST) | 41032 | 41034 (127.0.0.1) | |
| aleo | testnet | 41040 | 41042 | 41044 | |
| fuel | mainnet | 41540 (GraphQL + /v1/metrics) | 41542 | — (metrics served on +0) | +7 41547 reserved for fuel-core `--rpc-port` (gRPC, must stay off) |
| fuel | testnet | 41550 | 41552 | — | +7 41557 reserved |
| hedera | — | DEFERRED: block 41630-41659 stays unused | | | |
Implicit extras: none of these clients opens a port outside the table when the flags in specs/nonevm/*/clients.yml are given
(near: tcp only; polkadot litep2p: udp/<p2p port> webrtc-direct on the same number; peaq: tcp only).

## evml1 (EVM L1s and other chains; allocations from the evml1 spec) — READY networks only
Offsets as in the plan: +0 http · +1 ws · +2 p2p (tcp+udp) · +3 engine/auth (127.0.0.1) · +4 EL/primary metrics (127.0.0.1) · +5 CL rpc/rest · +6 CL p2p · +7 CL extra · +8,+9 spare. Anything not listed is unused (reserved).
| network | env0 (base) | env1 | env2 | per-env extras beyond the generic offsets |
|---|---|---|---|---|
| gnosis (41570) | mainnet 41570 | chiado 41580 | - | +5 lighthouse beacon http · +6 lighthouse p2p (tcp+udp) · +7 lighthouse QUIC udp (set `--quic-port`, else tcp+1) · +8 lighthouse metrics |
| joc (41780) | mainnet 41780 | testnet 41790 | - | nethermind only (+0 http, +1 ws, +2 p2p tcp+udp, +4 metrics); no engine, no CL |
| flare (41450) | flare 41450 | songbird 41460 | coston2 41470 | +0 http (also C-Chain `/ext/bc/C/rpc`, `/ext/bc/C/ws`, `/ext/metrics`) · +2 staking p2p (tcp); +1,+3,+4 unused |
| sonic (42440) | mainnet 42440 | testnet 42450 | - | +0 http · +1 ws · +2 p2p tcp+udp · +4 metrics |
| kaia (41810) | mainnet 41810 | kairos 41820 | - | +0 rpc · +1 ws · +2 p2p (`--port`, tcp+udp) · +4 prometheus · +8 `--subport` (multichannel, normally port+1) |
| vana (42650) | mainnet 42650 | moksha 42660 | - | +3 authrpc · +5 prysm gRPC-gateway (beacon REST) · +6 prysm p2p tcp · +7 prysm p2p udp · +8 prysm gRPC rpc (127.0.0.1) |
| tempo (42590) | mainnet 42590 | moderato 42600 | - | follow mode: +0 http · +1 ws · +2 p2p tcp+udp · +4 metrics; +6 reserved for consensus p2p (validators only, not started) |
| berachain (41150) | mainnet 41150 | bepolia 41160 | - | +3 authrpc · +4 reth metrics · +5 beacond CometBFT rpc (127.0.0.1) · +6 beacond p2p · +7 beacond prometheus · +8 beacond `proxy_app` ABCI (127.0.0.1) |
| data (41360) | mainnet 41360 | aeneid 41370 | - | +3 authrpc · +4 story-geth metrics · +5 story CometBFT rpc (127.0.0.1) · +6 story p2p · +7 story REST api (127.0.0.1) · +8 story prometheus · +9 story `proxy_app` ABCI (127.0.0.1) |
| fluent (41480) | mainnet 41480 | testnet 41490 | - | +0 http · +1 ws · +2 p2p tcp+udp · +4 metrics (reth-based single binary; authrpc is not used) |
| gravity (41600) | mainnet 41600 | longevity-testnet 41610 | - | +3 authrpc · +4 reth metrics · +5 inspection · +6 gravity public-network p2p (tcp, upstream default 6180/6195) · +2 reth p2p |
Not allocated (no READY route): ault, megaeth, monad, moca, plasma, sahara, shibarium, fantom (see specs/evml1/networks.md). Their blocks stay reserved in the table above.

## opstack (OP Stack L2s; allocations from specs/opstack) — op-reth/world-chain EL + op-node CL
Offsets: +0 EL http · +1 EL ws · +2 EL p2p (tcp+udp) · +3 EL engine (127.0.0.1) · +4 EL metrics · +5 op-node rpc · +6 op-node p2p (tcp+udp) · +7 op-node metrics · +8 EL discv5 (UDP; op-reth always runs discv5, upstream default 9200/udp clashes — pass `--discovery.v5.port`) · +9 spare. Same layout for every network below; env0 = mainnet, env1 = sepolia/testnet.
| network | env0 | env1 | status |
|---|---|---|---|
| unichain (42620) | mainnet 42620-42628 | sepolia 42630-42638 | READY |
| ink (41750) | mainnet 41750-41758 | sepolia 41760-41768 | READY |
| soneium (42410) | mainnet 42410-42418 | minato(sepolia) 42420-42428 | READY |
| worldchain (42680) | mainnet 42680-42688 | sepolia 42690-42698 | READY |
| lisk (41870) | mainnet 41870-41878 | — (Sepolia dead; env1 block unused) | READY |
| mode (41990) | mainnet 41990-41998 | sepolia 42000-42008 | NEEDS-DECISION |
| zora (42830) | mainnet 42830-42838 | sepolia 42840-42848 | NEEDS-DECISION |
| celo (41270) | mainnet 41270-41278 | celo-sepolia 41280-41288 | NEEDS-DECISION (variant B; +9 41279/41289 = eigenda-proxy 127.0.0.1) |
| mantle (41900) | mainnet 41900-41906 | sepolia 41910-41916 | NEEDS-DECISION (op-geth fork: +0 http +1 ws +3 engine +4 metrics +5 op-node rpc +6 op-node p2p +7 op-node metrics; +2 el p2p unused, maxpeers=0) |
| redstone (42260), blast (41180), b3 (41120), cyber (41330), fraxtal (41510), hemi (41660) | — | — | DEFERRED: blocks stay unused |

## cosmos family (CometBFT / Cosmos SDK; allocations from specs/cosmos) — never the upstream 26656/26657/1317/9090 (polygon heimdall owns them)
Pure-Cosmos (cosmos, osmosis, celestia): +0 CometBFT RPC (role `http`) · +2 p2p (tcp) · +4 prometheus (127.0.0.1) · +5 REST api · +7 gRPC · celestia +8 `--rpc.grpc_laddr` (127.0.0.1, mandatory since celestia-app v4).
EVM chains (sei, injective, xrplevm): +0 EVM http · +1 EVM ws · +2 p2p (tcp) · +4 prometheus (127.0.0.1) · +5 CometBFT RPC (role `cl_rpc`) · +7 gRPC · +8 REST api · xrplevm +9 `json-rpc.metrics-address` (127.0.0.1). Disabled/unused: grpc-web, pprof (`pprof_laddr=""`), sei admin_server (9095, off), privval.
| network | env0 (mainnet) | env1 (testnet) | notes |
|---|---|---|---|
| cosmos (41300) | 41300-41307 | 41310-41317 (provider) | 41300 rpc, 41302 p2p, 41304 metrics, 41305 api, 41307 grpc |
| osmosis (42110) | 42110-42117 | 42120-42127 (osmo-test-5) | same offsets as cosmos |
| celestia (41240) | 41240-41248 | 41250-41258 (mocha-5) | + 41248/41258 grpc_laddr; DA nodes (celestia-node) deferred: would use +6 (p2p 2121 equiv.) / +9 (rpc 26658 equiv.) |
| sei (42350) | 42350-42358 | 42360-42368 (atlantic-2) | 42350 evm http, 42351 ws, 42352 p2p, 42354 metrics, 42355 cl rpc, 42357 grpc, 42358 api |
| injective (41720) | 41720-41728 | 41730-41738 (injective-888) | same offsets as sei (EVM keys INFERRED) |
| xrplevm (42770) | 42770-42779 | 42780-42789 (testnet) | same as sei + 42779/42789 json-rpc metrics |
| zerog (42860) | 42860-42868 | 42870-42878 (galileo) | +0 reth http, +1 ws, +2 reth p2p (tcp+udp), +3 engine (127.0.0.1), +4 reth metrics (127.0.0.1), +5 0gchaind rpc, +6 cl p2p, +7 cl prometheus (127.0.0.1), +8 node-api (127.0.0.1); +9 spare |
Gravity (41600) is NOT a Cosmos chain (Aptos-BFT + gravity-reth): allocated by evml1, not here.

## rollups (non-OP rollups; allocations from specs/rollups) — never the upstream defaults (3060/3061/3081/3322/3054, 6060/6061/9090, 8545/8546/8547/8548, 26656-26660, 30303/30300)
| network | env0 | env1 | layout (offsets inside each env block) | status |
|---|---|---|---|---|
| xai (42710) | mainnet 42710-42714 | — | +0 http · +1 ws · +4 nitro metrics (127.0.0.1) | READY |
| starknet (42530) | mainnet 42530-42534 | sepolia 42540-42544 | +0 juno http · +1 ws · +4 metrics (127.0.0.1); p2p/grpc/pprof/http-update off | READY |
| zksync (42800) | mainnet 42800-42805 | sepolia 42810-42815 | ZK EN: +0 http · +1 ws · +2 consensus gossip (unused, reserved) · +4 prometheus (127.0.0.1) · +5 healthcheck (127.0.0.1); PostgreSQL = host server, no port | NEEDS-DECISION |
| abstract (41000) | mainnet 41000-41005 | testnet 41010-41015 | same ZK EN offsets | NEEDS-DECISION |
| lens (41840) | mainnet 41840-41845 | testnet 41850-41855 | same ZK EN offsets | NEEDS-DECISION |
| scroll (42320) | mainnet 42320-42324 | sepolia 42330-42334 | +0 http · +1 ws · +2 p2p (tcp+udp) · +4 metrics (127.0.0.1) | NEEDS-DECISION |
| immutable (41690) | mainnet 41690-41694 | testnet 41700-41704 | +0 http · +1 ws · +2 p2p (tcp+udp) · +4 metrics (127.0.0.1) | NEEDS-DECISION |
| morph (42050) | mainnet 42050-42058 | hoodi 42060-42068 | +0 geth http · +1 ws · +2 p2p · +3 authrpc (127.0.0.1) · +4 geth metrics · +5 morphnode rpc (127.0.0.1) · +6 morphnode p2p (tcp) · +7 morphnode metrics · +8 tendermint proxy_app/ABCI (127.0.0.1) | NEEDS-DECISION |
| xlayer (42740) | mainnet 42740-42748 | testnet 42750-42758 | OP shape as opstack: +0 reth http · +1 ws · +2 p2p · +3 engine · +4 metrics · +5 op-node rpc · +6 op-node p2p · +7 op-node metrics · +8 reth discv5 UDP (pass `--discovery.v5.port`) | NEEDS-DECISION |
| sophon (42470), polygonzkevm (42230) | — | — | DEFERRED: blocks stay unused |
