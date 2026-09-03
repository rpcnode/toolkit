import { Code, Table, Text, Title, Anchor, List, Box } from '@mantine/core'
import type { ReactNode } from 'react'

/** Minimal markdown renderer for developer-api.md (headings, tables, code, lists, links). */
export function MarkdownDoc({ source }: { source: string }) {
  const blocks = splitBlocks(source)
  return (
    <Box className="md-doc">
      {blocks.map((b, i) => (
        <Block key={i} block={b} />
      ))}
    </Box>
  )
}

type Block =
  | { type: 'h'; level: number; text: string }
  | { type: 'p'; text: string }
  | { type: 'code'; lang: string; text: string }
  | { type: 'table'; headers: string[]; rows: string[][] }
  | { type: 'ul'; items: string[] }
  | { type: 'empty' }

function splitBlocks(src: string): Block[] {
  const lines = src.replace(/\r\n/g, '\n').split('\n')
  const out: Block[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) {
      out.push({ type: 'empty' })
      i++
      continue
    }
    if (line.startsWith('```')) {
      const lang = line.slice(3).trim()
      const body: string[] = []
      i++
      while (i < lines.length && !lines[i].startsWith('```')) {
        body.push(lines[i])
        i++
      }
      i++ // closing fence
      out.push({ type: 'code', lang, text: body.join('\n') })
      continue
    }
    const hm = /^(#{1,4})\s+(.*)$/.exec(line)
    if (hm) {
      out.push({ type: 'h', level: hm[1].length, text: hm[2] })
      i++
      continue
    }
    if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*[-:| ]+\s*\|?\s*$/.test(lines[i + 1])) {
      const headers = splitRow(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
        rows.push(splitRow(lines[i]))
        i++
      }
      out.push({ type: 'table', headers, rows })
      continue
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*]\s+/, ''))
        i++
      }
      out.push({ type: 'ul', items })
      continue
    }
    const para: string[] = [line]
    i++
    while (i < lines.length && lines[i].trim() && !lines[i].startsWith('#') && !lines[i].startsWith('```') && !lines[i].includes('|') && !/^\s*[-*]\s+/.test(lines[i])) {
      para.push(lines[i])
      i++
    }
    out.push({ type: 'p', text: para.join(' ') })
  }
  return out
}

function splitRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim())
}

function Block({ block }: { block: Block }) {
  switch (block.type) {
    case 'empty':
      return null
    case 'h': {
      const order = Math.min(4, Math.max(2, block.level + 1)) as 2 | 3 | 4
      return (
        <Title order={order} mt="md" mb="xs">
          {inline(block.text)}
        </Title>
      )
    }
    case 'p':
      return (
        <Text size="sm" mb="sm" c="gray.3">
          {inline(block.text)}
        </Text>
      )
    case 'code':
      return (
        <Code block mb="md" className="mono" style={{ whiteSpace: 'pre-wrap' }}>
          {block.text}
        </Code>
      )
    case 'ul':
      return (
        <List size="sm" mb="md" spacing={4}>
          {block.items.map((it, i) => (
            <List.Item key={i}>{inline(it)}</List.Item>
          ))}
        </List>
      )
    case 'table':
      return (
        <Table.ScrollContainer minWidth={420} mb="md">
          <Table striped highlightOnHover withTableBorder withColumnBorders verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                {block.headers.map((h, i) => (
                  <Table.Th key={i}>{inline(h)}</Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {block.rows.map((row, ri) => (
                <Table.Tr key={ri}>
                  {row.map((c, ci) => (
                    <Table.Td key={ci}>
                      <Text size="sm">{inline(c)}</Text>
                    </Table.Td>
                  ))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )
    default:
      return null
  }
}

function inline(text: string): ReactNode {
  // `code`, **bold**, [label](url)
  const parts: ReactNode[] = []
  const re = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g
  let last = 0
  let m: RegExpExecArray | null
  let k = 0
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index))
    const tok = m[0]
    if (tok.startsWith('`')) {
      parts.push(
        <Code key={k++} className="mono">
          {tok.slice(1, -1)}
        </Code>,
      )
    } else if (tok.startsWith('**')) {
      parts.push(
        <Text span fw={700} key={k++}>
          {tok.slice(2, -2)}
        </Text>,
      )
    } else {
      const lm = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(tok)
      if (lm) {
        parts.push(
          <Anchor key={k++} href={lm[2]} target="_blank" rel="noreferrer">
            {lm[1]}
          </Anchor>,
        )
      }
    }
    last = m.index + tok.length
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts.length ? parts : text
}
