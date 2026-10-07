import { createTheme } from '@mantine/core'

// One console look for panel + installer: mono everywhere, hairline borders,
// no radius. Surfaces and border colors come from the --con-* tokens that
// .panel-shell maps onto Mantine's vars (styles.css).
export const theme = createTheme({
  fontFamily: '"IBM Plex Mono", ui-monospace, Menlo, Consolas, monospace',
  fontFamilyMonospace: '"IBM Plex Mono", ui-monospace, Menlo, Consolas, monospace',
  primaryColor: 'teal',
  defaultRadius: 0,
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
    fontFamily: '"IBM Plex Mono", ui-monospace, Menlo, Consolas, monospace',
    fontWeight: '600',
  },
  components: {
    Card: {
      defaultProps: { padding: 'md', radius: 0, withBorder: true },
    },
    Paper: {
      defaultProps: { radius: 0 },
    },
    Badge: {
      defaultProps: { variant: 'outline', radius: 0 },
    },
    Button: {
      defaultProps: { fw: 500, radius: 0 },
    },
    ActionIcon: {
      defaultProps: { radius: 0 },
    },
    TextInput: {
      defaultProps: { radius: 0 },
    },
    PasswordInput: {
      defaultProps: { radius: 0 },
    },
    Select: {
      defaultProps: { radius: 0 },
    },
    Modal: {
      defaultProps: { radius: 0 },
    },
  },
})
