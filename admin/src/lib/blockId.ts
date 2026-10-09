/**
 * Stable UI block ids for navigation and agent edits.
 *
 * Convention: dotted path, page first — `settings.snapshot-cdn`, `nodes.card.<uuid>`.
 * Grep: `data-block="settings.snapshot-cdn"` or `#block-settings-snapshot-cdn`.
 *
 * Usage:
 *   <Card {...blockProps('settings.appearance')}>
 *   <AppChrome block="settings" …>
 *   <Block id="modal.add-server.step.connect">…</Block>
 *
 * Catalog (main):
 *   shell.* — AppShell chrome
 *   dashboard.* | settings.* | servers.* | nodes.* | node.detail.*
 *   clients.* | networks.* | notifications.*
 *   login.* | setup.* | setup.channel.* | setup.password.*
 *   modal.* — dialogs (add-server, add-node, remove-*, node-debug, …)
 *   shared.* — ChannelOriginFields, ChannelLinks
 *   app.boot | app.catalog-loading
 */
export type BlockId = string

export function blockDomId(id: BlockId): string {
  return `block-${id.replace(/\./g, '-')}`
}

export function blockProps(id: BlockId): { 'data-block': BlockId; id: string } {
  return { 'data-block': id, id: blockDomId(id) }
}

/** `data-block` only — use when the element already has its own `id`. */
export function blockData(id: BlockId): { 'data-block': BlockId } {
  return { 'data-block': id }
}

/** `data-block` + optional explicit DOM id (defaults to blockDomId). */
export function blockAttrs(
  id: BlockId,
  opts?: { domId?: string },
): { 'data-block': BlockId; id: string } {
  return { 'data-block': id, id: opts?.domId ?? blockDomId(id) }
}

/** Page + section — `blockId('settings', 'snapshot-cdn')` → `settings.snapshot-cdn`. */
export function blockId(page: string, section: string, ...rest: string[]): BlockId {
  return [page, section, ...rest].filter(Boolean).join('.')
}
