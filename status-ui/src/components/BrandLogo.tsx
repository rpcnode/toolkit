type BrandLogoProps = {
  size?: number
  className?: string
}

export function BrandLogo({ size = 22, className }: BrandLogoProps) {
  return (
    <img
      src="/logo.svg"
      width={size}
      height={size}
      alt=""
      className={className ? `brand-logo ${className}` : 'brand-logo'}
      draggable={false}
    />
  )
}
