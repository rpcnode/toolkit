import type { ElementType, HTMLAttributes, ReactNode } from 'react'
import { blockAttrs, blockData, blockProps, type BlockId } from '../lib/blockId'

type Props = {
  id: BlockId
  as?: ElementType
  /** Keep an existing DOM id instead of the default block-* id. */
  domId?: string
  /** Only data-block, no id attribute. */
  dataOnly?: boolean
  children?: ReactNode
} & HTMLAttributes<HTMLElement>

/** Semantic wrapper — `<Block id="settings.appearance">` → data-block + block-* id. */
export function Block({ id, as: Tag = 'div', domId, dataOnly, children, ...rest }: Props) {
  const attrs = dataOnly ? blockData(id) : domId ? blockAttrs(id, { domId }) : blockProps(id)
  return (
    <Tag {...attrs} {...rest}>
      {children}
    </Tag>
  )
}
