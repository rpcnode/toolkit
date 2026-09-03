import { createContext, useContext, type ReactNode } from 'react'

/** Shared wizard bag for step components (structural split). */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type WizardApi = Record<string, any>

const WizardContext = createContext<WizardApi | null>(null)

export function WizardProvider({ value, children }: { value: WizardApi; children: ReactNode }) {
  return <WizardContext.Provider value={value}>{children}</WizardContext.Provider>
}

export function useWizard(): WizardApi {
  const v = useContext(WizardContext)
  if (!v) throw new Error('useWizard must be used inside WizardProvider')
  return v
}
