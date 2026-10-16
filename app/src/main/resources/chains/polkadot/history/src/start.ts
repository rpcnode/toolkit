import {Client} from 'pg'
import {config, StartBlock} from './config'

const TIMEOUT_MS = 30_000

async function rpc<T>(method: string, params: unknown[]): Promise<T>
{
    const res = await fetch(config.rpcUrl, {
        method: 'POST',
        headers: {'content-type': 'application/json'},
        body: JSON.stringify({jsonrpc: '2.0', id: 1, method, params}),
        signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    if (!res.ok)
    {
        throw new Error(`${method}: HTTP ${res.status} from ${config.rpcUrl}`)
    }
    const body = (await res.json()) as {result?: T; error?: {message?: string}}
    if (body.result === undefined)
    {
        throw new Error(`${method}: ${body.error?.message ?? 'no result'}`)
    }
    return body.result
}

/** The finalized head of the node, as a block number. */
export async function finalizedHeight(): Promise<number>
{
    const hash = await rpc<string>('chain_getFinalizedHead', [])
    const header = await rpc<{number: string}>('chain_getHeader', [hash])
    return parseInt(header.number, 16)
}

/** Height the processor has already stored (`squid_processor.status`), or null when the database is still empty. */
export async function storedHeight(): Promise<number | null>
{
    const client = new Client({
        host: process.env.DB_HOST ?? '127.0.0.1',
        port: Number(process.env.DB_PORT ?? 5432),
        database: process.env.DB_NAME,
        user: process.env.DB_USER,
        password: process.env.DB_PASS,
    })
    await client.connect()
    try
    {
        const exists = await client.query(`SELECT to_regclass('squid_processor.status') AS t`)
        if (exists.rows[0].t == null)
        {
            return null
        }
        const r = await client.query(`SELECT height FROM squid_processor.status WHERE id = 0`)
        return r.rows.length === 0 ? null : Number(r.rows[0].height)
    }
    finally
    {
        await client.end()
    }
}

/**
 * Where to start. START_BLOCK is the answer for a FIRST run only: once blocks are stored the processor resumes at the last
 * stored block + 1, and a larger START_BLOCK would silently leave a gap (verified: the range bound wins over the stored state),
 * so it is ignored then.
 */
export async function resolveStartBlock(
    wanted: StartBlock = config.startBlock,
    deps: {stored: () => Promise<number | null>; head: () => Promise<number>; log: (m: string) => void} = {
        stored: storedHeight,
        head: finalizedHeight,
        log: m => console.log(m),
    },
): Promise<number>
{
    const stored = await deps.stored()
    if (stored != null && stored >= 0)
    {
        deps.log(`resuming after block ${stored} (START_BLOCK applies to a first run only)`)
        return 0
    }
    switch (wanted.kind)
    {
        case 'genesis':
            deps.log('first run: indexing from block 0')
            return 0
        case 'number':
            deps.log(`first run: indexing from block ${wanted.height}`)
            return wanted.height
        case 'head':
        {
            const h = await deps.head()
            deps.log(`first run: indexing from the finalized head, block ${h}`)
            return h
        }
    }
}
