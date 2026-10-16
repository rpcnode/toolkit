import * as ss58 from '@subsquid/ss58'

/** JSON column values: bigint is not JSON, so it becomes a decimal string. */
export function jsonSafe(value: unknown): unknown
{
    if (value === undefined || value === null)
    {
        return null
    }
    return JSON.parse(JSON.stringify(value, (_k, v) => (typeof v === 'bigint' ? v.toString() : v)))
}

/** An account id as the chain prints it: hex bytes, or a MultiAddress / Address enum around them. */
export function account(raw: unknown, prefix: number): string | null
{
    if (raw == null)
    {
        return null
    }
    if (typeof raw === 'string')
    {
        return raw.startsWith('0x') && raw.length === 66 ? ss58.codec(prefix).encode(raw) : raw
    }
    if (typeof raw === 'object')
    {
        const v = (raw as {value?: unknown}).value
        if (v !== undefined)
        {
            return account(v, prefix)
        }
    }
    return null
}

export interface TransferFields
{
    from: string
    to: string
    amount: bigint
}

/**
 * `Balances.Transfer` of every runtime era: named fields since metadata v14, a positional tuple before (from, to, value).
 * Other events (and malformed ones) are not transfers.
 */
export function transferOf(eventName: string, args: unknown, prefix: number): TransferFields | null
{
    if (eventName !== 'Balances.Transfer' || args == null)
    {
        return null
    }
    let from: unknown
    let to: unknown
    let amount: unknown
    if (Array.isArray(args))
    {
        [from, to, amount] = args
    }
    else
    {
        const a = args as {from?: unknown; to?: unknown; amount?: unknown; value?: unknown}
        from = a.from
        to = a.to
        amount = a.amount ?? a.value
    }
    const f = account(from, prefix)
    const t = account(to, prefix)
    if (f == null || t == null || (typeof amount !== 'bigint' && typeof amount !== 'string' && typeof amount !== 'number'))
    {
        return null
    }
    return {from: f, to: t, amount: BigInt(amount)}
}
