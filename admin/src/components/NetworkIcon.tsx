import { useState } from 'react'
import { networkLabel } from '../lib/networksCatalog'

type Props = {
  network: string
  size?: number
  className?: string
}

type Meta = {
  /** cryptocurrency-icons / CDN slug when available */
  slug?: string
  ticker: string
  color: string
}

/** Brand marks for Nodes rail + group headers (CDN with local letter fallback). */
const META: Record<string, Meta> = {
  aptos: { slug: 'apt', ticker: 'APT', color: '#2DD8A7' },
  arb: { slug: 'arb', ticker: 'ARB', color: '#28A0F0' },
  avalanche: { slug: 'avax', ticker: 'AVAX', color: '#E84142' },
  base: { ticker: 'BASE', color: '#0052FF' },
  bitcoin: { slug: 'btc', ticker: 'BTC', color: '#F7931A' },
  bch: { slug: 'bch', ticker: 'BCH', color: '#0AC18E' },
  bsc: { slug: 'bnb', ticker: 'BNB', color: '#F3BA2F' },
  cardano: { slug: 'ada', ticker: 'ADA', color: '#0033AD' },
  dash: { slug: 'dash', ticker: 'DASH', color: '#008CE7' },
  doge: { slug: 'doge', ticker: 'DOGE', color: '#C2A633' },
  etc: { slug: 'etc', ticker: 'ETC', color: '#328332' },
  ethereum: { slug: 'eth', ticker: 'ETH', color: '#627EEA' },
  polygon: { slug: 'matic', ticker: 'POL', color: '#8247E5' },
  hyperliquid: { ticker: 'HL', color: '#97FCE4' },
  ltc: { slug: 'ltc', ticker: 'LTC', color: '#345D9D' },
  optimism: { slug: 'op', ticker: 'OP', color: '#FF0420' },
  robinhood: { ticker: 'RH', color: '#CCFF00' },
  solana: { slug: 'sol', ticker: 'SOL', color: '#9945FF' },
  stellar: { slug: 'xlm', ticker: 'XLM', color: '#14B6E7' },
  sui: { slug: 'sui', ticker: 'SUI', color: '#4DA2FF' },
  ton: { slug: 'ton', ticker: 'TON', color: '#0098EA' },
  tron: { slug: 'trx', ticker: 'TRX', color: '#FF0013' },
  xrpl: { slug: 'xrp', ticker: 'XRP', color: '#23292F' },
  zcash: { slug: 'zec', ticker: 'ZEC', color: '#F4B728' },
}

function metaFor(network: string): Meta {
  const id = (network || '').toLowerCase()
  if (META[id]) return META[id]
  const label = networkLabel(id)
  const ticker = (label.slice(0, 3) || id.slice(0, 3) || '?').toUpperCase()
  return { ticker, color: '#6B7C93' }
}

function iconUrl(slug: string): string {
  return `https://cdn.jsdelivr.net/npm/cryptocurrency-icons@0.18.1/svg/color/${slug}.svg`
}

export function NetworkIcon({ network, size = 18, className }: Props) {
  const meta = metaFor(network)
  const [failed, setFailed] = useState(false)
  const showImg = !!meta.slug && !failed
  const fontSize = Math.max(8, Math.round(size * (meta.ticker.length > 3 ? 0.32 : 0.42)))

  return (
    <span
      className={`network-icon${className ? ` ${className}` : ''}`}
      title={networkLabel(network)}
      style={{
        width: size,
        height: size,
        minWidth: size,
        background: showImg ? 'transparent' : meta.color,
        fontSize,
      }}
    >
      {showImg ? (
        <img
          src={iconUrl(meta.slug!)}
          alt=""
          width={size}
          height={size}
          draggable={false}
          onError={() => setFailed(true)}
        />
      ) : (
        <span className="network-icon__ticker">{meta.ticker}</span>
      )}
    </span>
  )
}
