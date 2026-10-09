import type { Metadata } from 'next'
import { IBM_Plex_Mono } from 'next/font/google'
import Link from 'next/link'
import './globals.css'
import { siteOrigin } from '@/lib/catalogue'
import {
  SITE_DESCRIPTION_DEFAULT,
  SITE_TITLE_DEFAULT,
  SITE_TITLE_TEMPLATE,
} from '@/lib/seo'

const TOOLKIT_URL = 'https://toolkit.rpcnode.dev/'
const RPCNODE_URL = 'https://rpcnode.dev/'

const plexMono = IBM_Plex_Mono({
  subsets: ['latin', 'cyrillic'],
  weight: ['400', '500', '600', '700'],
  display: 'swap',
  variable: '--font-plex-mono',
})

export async function generateMetadata(): Promise<Metadata>
{
  const origin = await siteOrigin()
  return {
    metadataBase: new URL(origin),
    title: {
      default: SITE_TITLE_DEFAULT,
      template: SITE_TITLE_TEMPLATE,
    },
    description: SITE_DESCRIPTION_DEFAULT,
    keywords: [
      'blockchain node snapshot',
      'download node snapshot',
      'TRON snapshot',
      'FullNode snapshot',
      'LiteFullNode snapshot',
      'RpcNode CDN',
    ],
    authors: [{ name: 'RpcNode', url: 'https://rpcnode.dev/' }],
    creator: 'RpcNode',
    publisher: 'RpcNode Snapshot CDN',
    category: 'technology',
    robots: {
      index: true,
      follow: true,
      googleBot: {
        index: true,
        follow: true,
      },
    },
    openGraph: {
      siteName: 'RpcNode Snapshot CDN',
      type: 'website',
      locale: 'en_US',
      title: SITE_TITLE_DEFAULT,
      description: SITE_DESCRIPTION_DEFAULT,
      url: '/',
    },
    twitter: {
      card: 'summary',
      title: SITE_TITLE_DEFAULT,
      description: SITE_DESCRIPTION_DEFAULT,
    },
    icons: {
      icon: [{ url: '/favicon.svg', type: 'image/svg+xml' }],
      shortcut: '/favicon.svg',
    },
    other: {
      'theme-color': '#070b0a',
    },
  }
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>)
{
  return (
    <html lang="en" className={plexMono.variable}>
      <body className={plexMono.className}>
        <div className="shell">
          <header className="top">
            <div className="top__left">
              <Link className="panel-id" href="/">
                <img src="/logo.svg" alt="" width={16} height={16} />
                <span>rpcnode</span>
              </Link>
              <span className="panel-id__slash">/</span>
              <span className="panel-id__page">snapshot-cdn</span>
            </div>
            <nav aria-label="Main">
              <ul className="nav">
                <li>
                  <Link href="/">networks</Link>
                </li>
                <li>
                  <a
                    className="is-ext"
                    href={TOOLKIT_URL}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    toolkit
                    <span className="ext" aria-hidden>
                      ↗
                    </span>
                  </a>
                </li>
                <li>
                  <a
                    className="is-ext"
                    href={RPCNODE_URL}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    rpc node
                    <span className="ext" aria-hidden>
                      ↗
                    </span>
                  </a>
                </li>
              </ul>
            </nav>
          </header>
          {children}
          <footer className="foot">
            <span>mirror index</span>
            <span>range downloads supported</span>
          </footer>
        </div>
      </body>
    </html>
  )
}
