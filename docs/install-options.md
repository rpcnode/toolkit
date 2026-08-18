# Install options — что выбирается при установке

Знания по **всем** сетям/окружениям: какие bootstrap/history flavors есть у продукта, что уже в wizard, где ещё нужен picker.

Код: `api-agent/install_options.go` → panel `nodes.install_options_json` → UI `InstallOptionsPicker`.  
XRPL history — группа `xrpl_history` в том же каталоге (UI fallback, если tip старый).

Конфиг **после** установки: Node Config (GET/PUT `/v1/node/config` через tip) — не путать с install options. Install options задают **какой snap/режим качать**; Node Config правит уже лежащие файлы.

---

## Каталог (23 сети)

| network | envs | Snapshot ExtraStep | Install picker сегодня | Что качаем / какой режим | После Install можно менять |
|---|---|---|---|---|---|
| **tron** | mainnet, nile, shasta | Required (main/nile), Optional (shasta) | **mainnet:** standard / internal_tx / balance_history | Official LevelDB mirrors; conf `saveInternalTx` / `balance.history.lookup` | Node Config (HOCON). Flavor **не** сменить без wipe+reinstall |
| **bitcoin** | mainnet, testnet4, signet, regtest | never (IBD) | нет | `txindex=1` `prune=0` | `bitcoin.conf` |
| **doge** | mainnet, testnet | never | нет | то же Core | `dogecoin.conf` |
| **ltc** | mainnet, testnet, regtest | never | нет | то же | `litecoin.conf` |
| **dash** | mainnet, testnet, regtest | never | нет | то же | `dash.conf` |
| **bch** | mainnet, testnet, regtest | never | нет | то же | BCHN conf |
| **zcash** | mainnet, testnet | never | нет | Zebra full, не prune | `zebrad.toml` |
| **ethereum** | mainnet, sepolia, hoodi | never | нет | Geth `--syncmode snap --gcmode full` + Lighthouse checkpoint | geth/lighthouse unit+toml |
| **etc** | mainnet, mordor | never | нет | Core-Geth **archive** | conf/flags |
| **bsc** | mainnet, testnet | never | нет | bsc-geth full / `gcmode=full` (не archive default) | conf |
| **arb** | mainnet, sepolia | never (nitro `--init.latest=pruned` на start) | нет | Official pruned init, не lite/archive | nitro flags / L1 RPC |
| **optimism** | mainnet, sepolia | never | нет | op-geth + op-node, non-archive | op-geth/op-node |
| **base** | mainnet, sepolia | never | нет | base-reth, no prune args | reth/consensus |
| **robinhood** | mainnet, testnet | **Required** | нет (один official pruned `--init.url`) | Orbit pruned snap | nitro + L1 |
| **hyperliquid** | mainnet, testnet | never | нет | `one_env_per_host`; visor + HyperEVM | `visor.json` |
| **solana** | mainnet, testnet, devnet, localnet | never (catch-up inside run) | нет | Agave RPC; multi-disk JBOD | validator flags / disk layout read-only |
| **xrpl** | mainnet, testnet | never (~39 TiB archive, нет public tarball) | **`xrpl_history`:** stock / 1d / 2w / **full** | `ledger_history`; default 2 weeks | cfg; full не появится на маленьком диске |
| **stellar** | mainnet, testnet, futurenet | never | нет | `HISTORY_RETENTION_WINDOW=MaxUint32` (не stock 7d) | stellar-rpc toml |
| **cardano** | mainnet, preprod, preview | **Required** (Mithril `latest`) | нет (один official path) | `mithril-client cardano-db download latest --include-ancillary` | node+ogmios json |
| **ton** | mainnet, testnet | never (dump внутри start) | нет | liteserver **~30d**, не archive; `one_env_per_host` | mytonctrl / engine flags |
| **sui** | mainnet, testnet | **Required** | нет (один formal R2 `--latest`) | `sui-tool download-formal-snapshot` | fullnode.yaml |
| **aptos** | mainnet, testnet | never | нет | genesis + pruners **off**. Official restore snaps **не** в wizard | aptos yaml |
| **avalanche** | mainnet, fuji | never | нет | C-Chain **archive** (`pruning-enabled=false`) | avalanchego + C-Chain json |

Disk layout (JBOD) — отдельный persist (`disk_layout_json`), не `install_options`. Сети с `multi_disk_roles`: solana, eth, bsc, arb, robinhood, base, optimism, tron, ton, sui, aptos, avalanche, bitcoin, …

---

## Где ещё предусмотреть picker (не делать вслепую)

Изучить official docs + `DESIGN.md` **до** кода. Потом `installOptionGroups()` + persist + matching conf.

1. **Cardano Mithril flavors** — сейчас один official `latest`. Epoch/digest picker не делать, пока продукт не даст второй path.
2. **Aptos restore snapshots** — сайт раньше обещал Snapshot step, профиль `SnapshotNever`. Либо честно IBD/genesis, либо ExtraSteps + picker источника. DESIGN: Fast sync skips history.
3. **TON dump vs archive** — archive ~12 TiB. Сейчас только dump+~30d. Если продукт когда-то даст archive — это install option (диск!).
4. **Arbitrum / OP / Base archive vs pruned** — official archive другой диск и другой init. Сейчас product = pruned/full, не archive.
5. **Ethereum `--gcmode archive`** vs текущий snap+full — другой диск, другой Synced proof. Не переключать тихо.
6. **Solana snapshot source** (Jito / official / skip) — сейчас кластерный catch-up, ExtraSteps нет.
7. **Avalanche** bootstrap database vs peers — уточнить official flavors перед picker.
8. **XRPL** — picker уже есть. Не форсить `full` на VPS. Clio не day-one.
9. **TRON nile/shasta** — один official path; не плодить фейковые flavors.
10. **Sui / Robinhood** — один official snap; picker не нужен, пока не появятся archive/pruned пары как у TRON.

---

## Контракт (MUST)

- Plan отдаёт `install_options` groups; старый tip → UI fallback (как TRON mainnet).
- Provision: выбранный URL + matching conf **в том же ACK**.
- Persist: panel + host file. Retry без body → reuse. `awaiting_ports` → clear.
- Node Config **не** меняет flavor снимка (LevelDB уже другой). Для смены — wipe + Add node.
- Новая сеть с несколькими official snaps → picker **day one** (`docs/add-network.md` §1a #9).

## Changelog

| Date | Note |
|---|---|
| 2026-08-18 | **Cardano Mithril** Snapshot ExtraStep (один path `latest`, без picker). XRPL `xrpl_history` в `installOptionGroups()`. |
| 2026-08-18 | Каталог всех 23 сетей: что уже выбирается (TRON snap / XRPL history), где gaps (Aptos restore, TON archive, EVM archive). |
