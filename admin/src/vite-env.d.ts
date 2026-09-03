/// <reference types="vite/client" />

declare const __PANEL_VERSION__: string

interface ImportMetaEnv {
  /** Panel API origin. Empty = same origin. Unset = http://127.0.0.1:8093 */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
