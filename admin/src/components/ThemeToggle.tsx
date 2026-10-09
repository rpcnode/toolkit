import { SegmentedControl, Tooltip } from '@mantine/core'
import { useMantineColorScheme, useComputedColorScheme } from '@mantine/core'
import { IconDeviceDesktop, IconMoon, IconSun } from '@tabler/icons-react'

const STORAGE_KEY = 'rpcnode-color-scheme'

export { STORAGE_KEY as COLOR_SCHEME_STORAGE_KEY }

export function ThemeToggle() {
  const { colorScheme, setColorScheme } = useMantineColorScheme()
  const computed = useComputedColorScheme('dark')

  return (
    <Tooltip label={`Theme: ${colorScheme} (now ${computed})`} withArrow>
      <SegmentedControl
        size="xs"
        value={colorScheme}
        onChange={(v) => setColorScheme(v as 'light' | 'dark' | 'auto')}
        data={[
          {
            value: 'light',
            label: <IconSun size={14} aria-label="Light" />,
          },
          {
            value: 'auto',
            label: <IconDeviceDesktop size={14} aria-label="System" />,
          },
          {
            value: 'dark',
            label: <IconMoon size={14} aria-label="Dark" />,
          },
        ]}
        aria-label="Color scheme"
      />
    </Tooltip>
  )
}
