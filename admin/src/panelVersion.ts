/** Panel SPA version from repo-root `PANEL_VERSION` (Vite inject at build). */
export const PANEL_VERSION =
  typeof __PANEL_VERSION__ === 'string' && __PANEL_VERSION__.trim()
    ? __PANEL_VERSION__.trim()
    : '0.0.0'
