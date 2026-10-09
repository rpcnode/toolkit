import { Input, ScrollArea, Stack, Text, UnstyledButton } from '@mantine/core'
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'

export type SearchableListItem = {
  value: string
  label: string
  disabled?: boolean
}

type Props = {
  label?: string
  description?: string
  items: SearchableListItem[]
  value: string | null
  onChange: (value: string) => void
  /** Enter confirms the highlighted row and continues (passes the picked id). */
  onEnterOnSelected?: (value: string) => void
  searchPlaceholder?: string
  nothingFoundMessage?: string
  listAriaLabel?: string
  maxHeight?: number | string
  autoFocus?: boolean
  disabled?: boolean
  /** When value is empty, select the first enabled row (keyboard-first wizards). */
  autoSelectFirst?: boolean
}

function norm(s: string) {
  return s.trim().toLowerCase()
}

function firstSelectableIndex(items: SearchableListItem[]) {
  return items.findIndex((it) => !it.disabled)
}

export function SearchableKeyboardList({
  label,
  description,
  items,
  value,
  onChange,
  onEnterOnSelected,
  searchPlaceholder = 'Search…',
  nothingFoundMessage = 'Nothing found',
  listAriaLabel = 'Options',
  maxHeight = 280,
  autoFocus = false,
  disabled = false,
  autoSelectFirst = false,
}: Props) {
  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const searchRef = useRef<HTMLInputElement>(null)
  const rowRefs = useRef<Array<HTMLButtonElement | null>>([])

  const filtered = useMemo(() => {
    const q = norm(query)
    if (!q) return items
    return items.filter((it) => norm(it.label).includes(q) || norm(it.value).includes(q))
  }, [items, query])

  const selectable = useMemo(() => filtered.filter((it) => !it.disabled), [filtered])

  useEffect(() => {
    const first = firstSelectableIndex(filtered)
    setActiveIndex(first >= 0 ? first : 0)
  }, [query, items])

  useEffect(() => {
    if (!autoFocus || disabled) return
    const t = window.setTimeout(() => searchRef.current?.focus(), 50)
    return () => window.clearTimeout(t)
  }, [autoFocus, disabled])

  useEffect(() => {
    const idx = filtered.findIndex((it) => it.value === value)
    if (idx >= 0) {
      setActiveIndex(idx)
      return
    }
    if (!autoSelectFirst || disabled || value) return
    const first = selectable[0]
    if (first) onChange(first.value)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only seed once when empty
  }, [value, filtered, autoSelectFirst, disabled, selectable[0]?.value])

  useEffect(() => {
    rowRefs.current[activeIndex]?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, filtered.length])

  function pick(item: SearchableListItem) {
    if (disabled || item.disabled) return
    onChange(item.value)
  }

  function confirmActive() {
    if (!selectable.length) return
    const hit = filtered[activeIndex]
    const chosen =
      hit && !hit.disabled ? hit : selectable[0]
    if (!chosen) return
    if (chosen.value !== value) onChange(chosen.value)
    onEnterOnSelected?.(chosen.value)
  }

  function moveActive(delta: number) {
    if (!filtered.length) return
    let next = activeIndex
    for (let i = 0; i < filtered.length; i++) {
      next = (next + delta + filtered.length) % filtered.length
      if (!filtered[next]?.disabled) {
        setActiveIndex(next)
        const row = filtered[next]
        if (row && !row.disabled) onChange(row.value)
        return
      }
    }
  }

  function onSearchKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      moveActive(1)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      moveActive(-1)
      return
    }
    if (e.key === 'Home') {
      e.preventDefault()
      const first = firstSelectableIndex(filtered)
      if (first >= 0) {
        setActiveIndex(first)
        const row = filtered[first]
        if (row && !row.disabled) onChange(row.value)
      }
      return
    }
    if (e.key === 'End') {
      e.preventDefault()
      for (let i = filtered.length - 1; i >= 0; i--) {
        if (!filtered[i]?.disabled) {
          setActiveIndex(i)
          onChange(filtered[i]!.value)
          return
        }
      }
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      e.stopPropagation()
      confirmActive()
    }
  }

  return (
    <Stack gap={6}>
      {label ? (
        <Text size="sm" fw={500}>
          {label}
        </Text>
      ) : null}
      {description ? (
        <Text size="xs" c="dimmed">
          {description}
        </Text>
      ) : null}
      <Input
        ref={searchRef}
        data-searchable-list-search=""
        placeholder={searchPlaceholder}
        value={query}
        onChange={(e) => setQuery(e.currentTarget.value)}
        onKeyDown={onSearchKeyDown}
        disabled={disabled}
        aria-label={label ? `${label} search` : 'Search'}
        aria-activedescendant={
          filtered[activeIndex] ? `searchable-list-opt-${filtered[activeIndex].value}` : undefined
        }
        role="combobox"
        aria-expanded="true"
        aria-controls="searchable-list-options"
      />
      <ScrollArea.Autosize mah={maxHeight} type="scroll" offsetScrollbars scrollbarSize={6}>
        <Stack gap={2} id="searchable-list-options" role="listbox" aria-label={listAriaLabel}>
          {filtered.length === 0 ? (
            <Text size="sm" c="dimmed" py="sm" px="xs">
              {nothingFoundMessage}
            </Text>
          ) : (
            filtered.map((item, index) => {
              const selected = value === item.value
              const active = activeIndex === index
              return (
                <UnstyledButton
                  key={item.value}
                  id={`searchable-list-opt-${item.value}`}
                  ref={(el) => {
                    rowRefs.current[index] = el
                  }}
                  role="option"
                  aria-selected={selected}
                  tabIndex={-1}
                  disabled={disabled || item.disabled}
                  onClick={() => pick(item)}
                  onMouseEnter={() => {
                    if (!item.disabled) setActiveIndex(index)
                  }}
                  px="sm"
                  py={8}
                  style={{
                    border: `1px solid ${
                      selected
                        ? 'var(--mantine-color-teal-6)'
                        : active
                          ? 'var(--con-line)'
                          : 'var(--mantine-color-dark-4)'
                    }`,
                    background: selected
                      ? 'var(--mantine-color-dark-6)'
                      : active
                        ? 'var(--con-hover)'
                        : 'transparent',
                    textAlign: 'left',
                    opacity: item.disabled ? 0.45 : 1,
                    cursor: item.disabled ? 'not-allowed' : 'pointer',
                  }}
                >
                  <Text size="sm" style={{ whiteSpace: 'normal' }}>
                    {item.label}
                  </Text>
                </UnstyledButton>
              )
            })
          )}
        </Stack>
      </ScrollArea.Autosize>
      <Text size="xs" c="dimmed">
        ↑↓ move · ↵ continue · Esc back
      </Text>
    </Stack>
  )
}
