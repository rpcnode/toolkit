import { createTheme } from '@mantine/core'

export const theme = createTheme({
  fontFamily: '"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif',
  fontFamilyMonospace: '"IBM Plex Mono", ui-monospace, Menlo, Consolas, monospace',
  primaryColor: 'teal',
  defaultRadius: 'md',
  colors: {
    dark: [
      '#C8D0DA',
      '#A8B4C2',
      '#8494A8',
      '#5C6E84',
      '#3A4A5C',
      '#243141',
      '#1A2430',
      '#121A22',
      '#0C1218',
      '#070B10',
    ],
  },
  headings: {
    fontFamily: '"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif',
    fontWeight: '650',
  },
  components: {
    Card: {
      defaultProps: { padding: 'lg', radius: 'md', withBorder: true },
    },
    Badge: {
      defaultProps: { variant: 'light' },
    },
    Button: {
      defaultProps: { fw: 600 },
    },
  },
})
