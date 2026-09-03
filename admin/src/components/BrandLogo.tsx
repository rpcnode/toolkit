type BrandLogoProps = {
  size?: number
  className?: string
  title?: string
}

/** Console mark — hub + three peers; matches public/logo.svg and favicon.svg. */
export function BrandLogo({ size = 22, className, title = 'RpcNode' }: BrandLogoProps) {
  return (
    <svg
      className={className ? `brand-logo ${className}` : 'brand-logo'}
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label={title}
    >
      <rect width="32" height="32" fill="#070b0a" />
      <rect x="7.5" y="7.5" width="17" height="17" stroke="#3ddc97" strokeOpacity="0.28" />
      <path
        d="M16 9v3.5M10.5 22.5l4.2-4.2M21.5 22.5l-4.2-4.2"
        stroke="#3ddc97"
        strokeOpacity="0.72"
        strokeWidth="1"
        strokeLinecap="square"
      />
      <rect x="15" y="7" width="2" height="2" fill="#3ddc97" />
      <rect x="9" y="21" width="2" height="2" fill="#3ddc97" />
      <rect x="21" y="21" width="2" height="2" fill="#3ddc97" />
      <rect x="14" y="14" width="4" height="4" fill="#3ddc97" />
    </svg>
  )
}
