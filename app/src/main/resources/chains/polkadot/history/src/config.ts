/**
 * Everything the operator can tune comes from the environment of the process (written by the RpcNode start script, which
 * also holds the database password in a 0600 file). Defaults are the values the node page shows as bindings.
 */
function int(name: string, fallback: number, min = 0): number
{
    const raw = process.env[name]
    if (raw == null || raw.trim() === '')
    {
        return fallback
    }
    const n = Number(raw)
    if (!Number.isSafeInteger(n) || n < min)
    {
        throw new Error(`${name} must be an integer >= ${min}, got "${raw}"`)
    }
    return n
}

function text(name: string, fallback: string): string
{
    const raw = process.env[name]?.trim()
    return raw ? raw : fallback
}

export type StartBlock = {kind: 'genesis'} | {kind: 'head'} | {kind: 'number'; height: number}

export function startBlock(raw: string): StartBlock
{
    const v = raw.trim().toLowerCase()
    if (v === 'genesis' || v === '0')
    {
        return {kind: 'genesis'}
    }
    if (v === 'head' || v === 'latest')
    {
        return {kind: 'head'}
    }
    if (/^[0-9]{1,10}$/.test(v) && Number(v) <= 2_147_483_647)
    {
        return {kind: 'number', height: Number(v)}
    }
    throw new Error(`START_BLOCK must be genesis, head or a block number, got "${raw}"`)
}

export type EventPolicy = 'all' | 'relevant' | 'none'
export type ArgsPolicy = 'signed' | 'all' | 'none'

function oneOf<T extends string>(name: string, allowed: readonly T[], fallback: T): T
{
    const v = text(name, fallback) as T
    if (!allowed.includes(v))
    {
        throw new Error(`${name} must be one of ${allowed.join(', ')}, got "${v}"`)
    }
    return v
}

export const config = {
    /** The node's own RPC (HTTP and WebSocket share the port). */
    rpcUrl: text('NODE_RPC_URL', 'http://127.0.0.1:42200'),
    /** `genesis`, `head` (the finalized head at the first start) or a block number. Only a FIRST run honours it. */
    startBlock: startBlock(text('START_BLOCK', 'genesis')),
    /** Optional last block: the process exits 0 once it is reached (bounded runs); unset = follow the chain forever. */
    endBlock: process.env.END_BLOCK?.trim() ? int('END_BLOCK', 0) : undefined,
    ss58Prefix: int('SS58_PREFIX', 0),
    rpcCapacity: int('RPC_CAPACITY', 10, 1),
    rpcRateLimit: int('RPC_RATE_LIMIT', 0),
    rpcBatchSize: int('RPC_BATCH_SIZE', 50, 1),
    rpcRequestTimeoutMs: int('RPC_REQUEST_TIMEOUT_MS', 60_000, 1000),
    indexEvents: oneOf<EventPolicy>('INDEX_EVENTS', ['all', 'relevant', 'none'], 'relevant'),
    indexArgs: oneOf<ArgsPolicy>('INDEX_ARGS', ['signed', 'all', 'none'], 'signed'),
}
