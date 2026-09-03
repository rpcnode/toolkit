/** OS-aware modifier labels for UI hints (Mac ⌘ vs Win/Linux Ctrl). */
export function isMacOS(): boolean {
  if (typeof navigator === 'undefined') return false
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform) || navigator.userAgent.includes('Mac')
}

export function modKeyLabel(): string {
  return isMacOS() ? '⌘' : 'Ctrl'
}

export function modEnterLabel(): string {
  return isMacOS() ? '⌘↵' : 'Ctrl+↵'
}

export function modNLabel(): string {
  return isMacOS() ? '⌘N' : 'Ctrl+N'
}

export function isModEnter(e: KeyboardEvent): boolean {
  return e.key === 'Enter' && (e.metaKey || e.ctrlKey)
}

export function isModN(e: KeyboardEvent): boolean {
  return (e.key === 'n' || e.key === 'N') && (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey
}

export function isTypingTarget(t: EventTarget | null): boolean {
  if (!t || !(t instanceof HTMLElement)) return false
  const tag = t.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return t.isContentEditable
}
