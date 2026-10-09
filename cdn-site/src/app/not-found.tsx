import Link from 'next/link'

export default function NotFound()
{
  return (
    <main>
      <p className="eyebrow">404</p>
      <h1 className="page-title">mirror not found</h1>
      <p className="lede">
        This network / environment / type is not in the published catalogue.
      </p>
      <Link className="ghost" href="/">
        back to index
      </Link>
    </main>
  )
}
