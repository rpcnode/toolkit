import { TextInput } from '@mantine/core'
import { useCallback, useEffect, useState } from 'react'
import { api, type NetworkCatalogItem, type PanelSettings, type SetupCheck } from '../api'
import { navigate } from '../lib/router'
import { ChannelLinks } from '../components/ChannelLinks'
import { ORIGIN_LOCAL } from '../components/ChannelOriginFields'
import { SetupShell } from '../components/SetupShell'
import { blockProps } from '../lib/blockId'

const STEPS = [
  { id: 'admin', file: 'admin', hint: 'human, not the agent' },
  { id: 'origin', file: 'origin', hint: 'cdn, default client-sync' },
  { id: 'probe', file: 'probe', hint: 'panel / db / binaries' },
  { id: 'nets', file: 'nets', hint: 'optional' },
] as const

const STEP_DOC = [
  {
    title: 'admin',
    lines: [
      '// password is for people in the UI',
      '// agents use AGENT_API_TOKEN',
      '// host install prints the token',
      '',
      'fun setup() {',
      '    createAdmin(user, pass)',
      '}',
    ],
  },
  {
    title: 'origin',
    lines: [
      '// one URL — CDN agents download from',
      '// default: panel :8093',
      '// change later in Settings',
      '',
      'cdn = http://127.0.0.1:8093',
    ],
  },
  {
    title: 'probe',
    lines: [
      '// origin is required: GET …/install/binaries',
      '// gray = local panel files, optional',
      '',
      'required: panel, sqlite, cdn',
    ],
  },
  {
    title: 'nets',
    lines: [
      '// enable only when client files are on disk',
      '// pin-only (ton, …) skip the CDN',
      '// download happens on Clients first',
      '',
      'for (n in catalog) pick(n)',
    ],
  },
]

function SetupRail({
  step,
  onJump,
}: {
  step: number
  onJump: (i: number) => void
}) {
  return (
    <ol className="setup-rail">
      {STEPS.map((s, i) => {
        const state = i === step ? 'now' : i < step ? 'done' : 'wait'
        return (
          <li key={s.id}>
            <button
              type="button"
              className={`setup-rail__item is-${state}`}
              disabled={i > step}
              onClick={() => i <= step && onJump(i)}
            >
              <span className="setup-rail__n">{String(i + 1).padStart(2, '0')}</span>
              <span className="setup-rail__file">{s.file}</span>
              {state === 'now' ? <span className="setup-rail__cur" aria-hidden /> : null}
            </button>
          </li>
        )
      })}
    </ol>
  )
}

function SetupCmd({
  children,
  onClick,
  type = 'button',
  ghost,
  busy,
  disabled,
}: {
  children: string
  onClick?: () => void
  type?: 'button' | 'submit'
  ghost?: boolean
  busy?: boolean
  disabled?: boolean
}) {
  return (
    <button
      type={type}
      className={`setup-cmd${ghost ? ' is-ghost' : ''}`}
      onClick={onClick}
      disabled={disabled || busy}
    >
      {busy ? '…' : children}
    </button>
  )
}

export function SetupWizardPage() {
  const [step, setStep] = useState(0)
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [origin, setOrigin] = useState(ORIGIN_LOCAL)
  const [settings, setSettings] = useState<PanelSettings | null>(null)
  const [checks, setChecks] = useState<SetupCheck[]>([])
  const [checkReady, setCheckReady] = useState(false)
  const [networks, setNetworks] = useState<NetworkCatalogItem[]>([])
  const [picked, setPicked] = useState<Record<string, 'install' | 'skip' | ''>>({})
  const [hasAdmin, setHasAdmin] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadSettings = useCallback(async () => {
    const s = await api.panelSettings()
    setSettings(s)
    if (s.install_origin) setOrigin(s.install_origin)
    else setOrigin(s.presets?.local || ORIGIN_LOCAL)
  }, [])

  useEffect(() => {
    void api
      .setupStatus()
      .then((st) => {
        setHasAdmin(!st.needed)
        setStep(st.needed ? 0 : 1)
      })
      .catch(() => {
        /* first run */
      })
    void loadSettings().catch(() => {
      /* empty */
    })
  }, [loadSettings])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      const t = e.target as HTMLElement | null
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) {
        if (t.tagName === 'INPUT' && (t as HTMLInputElement).value) return
      }
      if (step > 0) {
        e.preventDefault()
        setStep(step - 1)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step])

  async function submitAdmin(e: React.FormEvent) {
    e.preventDefault()
    if (password.length < 8) {
      setError('password must be ≥ 8')
      return
    }
    if (password !== confirm) {
      setError('passwords do not match')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.setup(username.trim() || 'admin', password)
      setHasAdmin(true)
      setStep(1)
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  async function submitLinks() {
    if (!origin.trim()) {
      setError('cdn required')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.savePanelSettings({ install_origin: origin.trim() })
      await loadSettings()
      const res = await api.setupCheck()
      const cdn = (res.checks || []).find((c) => c.id === 'cdn')
      if (!cdn?.ok) {
        setError(`cdn down  ${cdn?.detail || origin}`)
        return
      }
      await api.setupStage('server')
      setChecks(res.checks || [])
      setCheckReady(!!res.ready)
      setStep(2)
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  async function runCheck() {
    setBusy(true)
    setError(null)
    try {
      const res = await api.setupCheck()
      setChecks(res.checks || [])
      setCheckReady(!!res.ready)
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  async function goNetworks() {
    if (!checkReady) {
      setError('finish required probes first')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.setupStage('networks')
      const all = await api.networksAll()
      setNetworks(all.items || [])
      setStep(3)
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  async function applyNetwork(id: string, action: 'install' | 'skip') {
    setPicked((p) => ({ ...p, [id]: action }))
    if (action === 'skip') {
      await api.networkAction(id, 'skip')
      return
    }
    const n = networks.find((x) => x.id === id)
    if (!n?.files_ready) {
      setError('Download the client on Clients first (GitHub token in Settings).')
      setPicked((p) => {
        const next = { ...p }
        delete next[id]
        return next
      })
      return
    }
    await api.networkAction(id, 'enable')
  }

  async function finish() {
    setBusy(true)
    setError(null)
    try {
      await api.setupFinish()
      navigate({ name: 'dashboard' })
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  const doc = STEP_DOC[step] || STEP_DOC[0]
  const meta = STEPS[step]

  return (
    <SetupShell
      block="setup"
      title="setup"
      index={`${String(step + 1).padStart(2, '0')} / 04  ${meta.file}`}
      status={settings?.install?.version ? `panel.install  ·  ${settings.install.version}` : 'panel.install  ·  pending'}
      left={
        <>
          <SetupRail step={step} onJump={setStep} />

          {error ? <p className="setup-err">! {error}</p> : null}

          {step === 0 && (
            <form className="setup-block" {...blockProps('setup.step.admin')} onSubmit={(e) => void submitAdmin(e)}>
              {hasAdmin ? (
                <p className="setup-note">// htpasswd exists — this sets a new password</p>
              ) : null}
              <TextInput
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="user"
                value={username}
                onChange={(e) => setUsername(e.currentTarget.value)}
                autoComplete="username"
                required
              />
              <TextInput
                type="password"
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="pass"
                value={password}
                onChange={(e) => setPassword(e.currentTarget.value)}
                autoComplete="new-password"
                required
              />
              <TextInput
                type="password"
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="again"
                value={confirm}
                onChange={(e) => setConfirm(e.currentTarget.value)}
                autoComplete="new-password"
                required
              />
              <div className="setup-actions">
                <SetupCmd type="submit" busy={busy}>
                  continue
                </SetupCmd>
              </div>
            </form>
          )}

          {step === 1 && (
            <div className="setup-block" {...blockProps('setup.step.origin')}>
              <TextInput
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="cdn"
                placeholder={ORIGIN_LOCAL}
                value={origin}
                onChange={(e) => setOrigin(e.currentTarget.value.trim())}
                required
              />
              <div className="setup-actions">
                <SetupCmd ghost onClick={() => setStep(0)}>
                  back
                </SetupCmd>
                <SetupCmd busy={busy} onClick={() => void submitLinks()}>
                  continue
                </SetupCmd>
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="setup-block" {...blockProps('setup.step.probe')}>
              <ul className="setup-probe">
                {checks.map((c) => {
                  const tag = c.ok ? 'PASS' : c.required ? 'FAIL' : 'SKIP'
                  return (
                    <li key={c.id} className={`is-${tag.toLowerCase()}`}>
                      <span className="setup-probe__tag">{tag}</span>
                      <span className="setup-probe__id">{c.id}</span>
                      <span className="setup-probe__label">{c.label}</span>
                    </li>
                  )
                })}
              </ul>
              <div className="setup-actions">
                <SetupCmd ghost onClick={() => setStep(1)}>
                  back
                </SetupCmd>
                <SetupCmd ghost busy={busy} onClick={() => void runCheck()}>
                  probe
                </SetupCmd>
                <SetupCmd disabled={!checkReady} onClick={() => void goNetworks()}>
                  continue
                </SetupCmd>
              </div>
            </div>
          )}

          {step === 3 && (
            <div className="setup-block" {...blockProps('setup.step.networks')}>
              <ul className="setup-nets">
                {networks.map((n) => {
                  const on = picked[n.id] === 'install' || n.enabled
                  const tag = n.files_ready || n.enabled ? 'disk' : picked[n.id] === 'skip' ? 'skip' : '—'
                  return (
                    <li key={n.id}>
                      <button
                        type="button"
                        className={`setup-net${on ? ' is-on' : ''}`}
                        onClick={() => void applyNetwork(n.id, on ? 'skip' : 'install')}
                      >
                        <span className="setup-net__box">{on ? 'x' : ' '}</span>
                        <span className="setup-net__id">{n.id}</span>
                        <span className="setup-net__tag">{tag}</span>
                      </button>
                    </li>
                  )
                })}
              </ul>
              <div className="setup-actions">
                <SetupCmd ghost onClick={() => setStep(2)}>
                  back
                </SetupCmd>
                <SetupCmd busy={busy} onClick={() => void finish()}>
                  write panel.install
                </SetupCmd>
              </div>
            </div>
          )}
        </>
      }
      right={
        <div className="setup-doc">
          <div className="setup-doc__head">
            <span>// {doc.title}</span>
            <span>{meta.hint}</span>
          </div>
          <pre className="setup-doc__src">
            {doc.lines.map((line, i) => (
              <span key={i}>{line || ' '}</span>
            ))}
          </pre>
          {step === 2 && checks.length > 0 && (
            <pre className="setup-doc__src">
              {checks.map((c) => (
                <span key={c.id}>
                  {c.id}  {c.detail || '—'}
                </span>
              ))}
            </pre>
          )}
          {(step === 0 || step === 1) && (
            <div className="setup-tape">
              <ChannelLinks links={settings?.links} scripts={settings?.scripts} panelScripts={settings?.panel_scripts} />
            </div>
          )}
        </div>
      }
    />
  )
}
