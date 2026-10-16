import {SubstrateBatchProcessor} from '@subsquid/substrate-processor'
import {TypeormDatabase} from '@subsquid/typeorm-store'
import {Block, Event, Extrinsic, Transfer} from './model'
import {config} from './config'
import {account, jsonSafe, transferOf} from './decode'
import {resolveStartBlock} from './start'

/** Parachain-consensus events: >95% of all relay-chain events by count (1.6 KB of args each), nobody queries them by account. */
const NOISY_PALLETS = new Set(['ParaInclusion'])

const pad = (n: number, width: number): string => String(n).padStart(width, '0')

function buildProcessor(from: number)
{
    return new SubstrateBatchProcessor()
        .setRpcEndpoint({
            url: config.rpcUrl,
            capacity: config.rpcCapacity,
            maxBatchCallSize: config.rpcBatchSize,
            requestTimeout: config.rpcRequestTimeoutMs,
            rateLimit: config.rpcRateLimit > 0 ? config.rpcRateLimit : undefined,
        })
        // the node's own database is the only source: no third-party gateway
        .setBlockRange({from, to: config.endBlock})
        .includeAllBlocks()
        .setFields({
            block: {timestamp: true, specVersion: true},
            extrinsic: {hash: true, success: true, signature: true, tip: true},
            call: {name: true, args: true},
            event: {name: true, args: true, phase: true, extrinsicIndex: true},
        })
        .addCall({extrinsic: true})
        .addEvent({extrinsic: true})
}

async function main(): Promise<void>
{
    const processor = buildProcessor(await resolveStartBlock())

    processor.run(new TypeormDatabase({supportHotBlocks: true}), async ctx =>
    {
        const blocks: Block[] = []
        const extrinsics: Extrinsic[] = []
        const events: Event[] = []
        const transfers: Transfer[] = []

        for (const block of ctx.blocks)
        {
            const h = block.header
            const timestamp = new Date(h.timestamp ?? 0)
            blocks.push(new Block({
                id: pad(h.height, 10),
                height: h.height,
                hash: h.hash,
                parentHash: h.parentHash,
                timestamp,
                specVersion: h.specVersion,
                extrinsicCount: block.extrinsics.length,
                eventCount: block.events.length,
            }))

            for (const ext of block.extrinsics)
            {
                const call = ext.call
                const [pallet, name] = (call?.name ?? 'Unknown.unknown').split('.')
                const signed = ext.signature != null
                extrinsics.push(new Extrinsic({
                    id: `${pad(h.height, 10)}-${pad(ext.index, 6)}`,
                    blockNumber: h.height,
                    index: ext.index,
                    hash: ext.hash,
                    pallet,
                    call: name,
                    signer: ext.signature != null ? account(ext.signature.address, config.ss58Prefix) : null,
                    success: ext.success,
                    tip: ext.tip ?? null,
                    timestamp,
                    args: config.indexArgs === 'all' || (config.indexArgs === 'signed' && signed) ? jsonSafe(call?.args) : null,
                }))
            }

            for (const ev of block.events)
            {
                const [pallet, name] = ev.name.split('.')
                const extIndex = ev.extrinsicIndex ?? null
                const t = transferOf(ev.name, ev.args, config.ss58Prefix)
                if (t != null)
                {
                    transfers.push(new Transfer({
                        id: `${pad(h.height, 10)}-${pad(ev.index, 6)}`,
                        blockNumber: h.height,
                        timestamp,
                        extrinsicHash: ev.extrinsic?.hash ?? null,
                        extrinsicIndex: extIndex,
                        eventIndex: ev.index,
                        from: t.from,
                        to: t.to,
                        amount: t.amount,
                    }))
                }
                if (config.indexEvents === 'all' || (config.indexEvents === 'relevant' && !NOISY_PALLETS.has(pallet)))
                {
                    events.push(new Event({
                        id: `${pad(h.height, 10)}-${pad(ev.index, 6)}`,
                        blockNumber: h.height,
                        index: ev.index,
                        extrinsicIndex: extIndex,
                        phase: ev.phase,
                        pallet,
                        name,
                        timestamp,
                        args: jsonSafe(ev.args),
                    }))
                }
            }
        }

        await ctx.store.insert(blocks)
        await ctx.store.insert(extrinsics)
        await ctx.store.insert(events)
        await ctx.store.insert(transfers)
        if (ctx.isHead)
        {
            const last = ctx.blocks[ctx.blocks.length - 1]?.header.height
            ctx.log.info(`head ${last}: ${blocks.length} blocks, ${extrinsics.length} extrinsics, ${events.length} events, ${transfers.length} transfers`)
        }
    })
}

main().catch(err =>
{
    console.error(err)
    process.exit(1)
})
