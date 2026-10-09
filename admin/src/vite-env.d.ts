/// <reference types="vite/client" />

declare const __PANEL_VERSION__: string

interface ImportMetaEnv {
  /** rpcnode-server origin. Empty / unset = same origin until setup picks a server. */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
