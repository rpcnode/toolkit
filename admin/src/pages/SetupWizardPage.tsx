import { TextInput } from '@mantine/core'
import { useCallback, useEffect, useState } from 'react'
import {
  api,
  getApiOriginOverride,
  setApiOriginOverride,
  type NetworkCatalogItem,
  type PanelSettings,
  type SetupCheck,
} from '../api'
import { navigate } from '../lib/router'
import { ChannelLinks } from '../components/ChannelLinks'
import { SetupShell } from '../components/SetupShell'
import { blockProps } from '../lib/blockId'
import {
  advertisedOrigin,
  CDN_LISTEN_PORT,
  isLoopbackHost,
  originHost,
  pageHost,
  SERVER_LISTEN_PORT,
  suggestedAdvertisedHost,
} from '../lib/advertisedOrigin'

const STEPS = [
  { id: 'origin', file: 'origin', hint: 'server first' },
  { id: 'admin', file: 'admin', hint: 'human, not the agent' },
  { id: 'probe', file: 'probe', hint: 'panel / db / binaries' },
  { id: 'nets', file: 'nets', hint: 'optional' },
] as const

const STEP_DOC = [
  {
    title: 'origin',
    lines: [
      '// pick the server, then connect',
      '// docker: never 127.0.0.1',
      '// that is the container itself',
      '',
      'server = http://<host>:8094',
      'cdn    = http://<host>:8095',
    ],
  },
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
    title: 'probe',
    lines: [
      '// origin is required: GET …/install/binaries',
      '// gray = local panel files, optional',
      '',
      'required: server, sqlite',
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
  const [origin, setOrigin] = useState('')
  const [cdnOrigin, setCdnOrigin] = useState('')
  const [advertiseHost, setAdvertiseHost] = useState('')
  const [settings, setSettings] = useState<PanelSettings | null>(null)
  const [checks, setChecks] = useState<SetupCheck[]>([])
  const [checkReady, setCheckReady] = useState(false)
  const [networks, setNetworks] = useState<NetworkCatalogItem[]>([])
  const [picked, setPicked] = useState<Record<string, 'install' | 'skip' | ''>>({})
  const [hasAdmin, setHasAdmin] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const serverPreview = advertiseHost
    ? advertisedOrigin(advertiseHost, SERVER_LISTEN_PORT)
    : 'http://<host>:8094'
  const cdnPreview = advertiseHost
    ? advertisedOrigin(advertiseHost, CDN_LISTEN_PORT)
    : 'http://<host>:8095'

  function applyAdvertiseHost(next: string) {
    setAdvertiseHost(next)
    if (!next) return
    setOrigin(advertisedOrigin(next, SERVER_LISTEN_PORT))
    setCdnOrigin((prev) => {
      if (!prev.trim()) return advertisedOrigin(next, CDN_LISTEN_PORT)
      const oldHost = originHost(prev)
      if (!oldHost || isLoopbackHost(oldHost)) return advertisedOrigin(next, CDN_LISTEN_PORT)
      try {
        const u = new URL(prev)
        u.hostname = next
        return u.origin
      } catch {
        return advertisedOrigin(next, CDN_LISTEN_PORT)
      }
    })
  }

  const loadSettings = useCallback(async () => {
    const s = await api.panelSettings()
    setSettings(s)
    const host = suggestedAdvertisedHost(s.install_origin, s.presets?.panel)
    setAdvertiseHost(host)
    if (s.install_origin) setOrigin(s.install_origin)
    else setOrigin(host ? advertisedOrigin(host, SERVER_LISTEN_PORT) : '')
    if (s.snapshot_cdn_origin || s.snapshot_cdn?.origin) {
      setCdnOrigin(s.snapshot_cdn_origin || s.snapshot_cdn?.origin || '')
    } else {
      setCdnOrigin(host ? advertisedOrigin(host, CDN_LISTEN_PORT) : '')
    }
  }, [])

  useEffect(() => {
    const saved = getApiOriginOverride()
    const host = suggestedAdvertisedHost(saved, typeof window !== 'undefined' ? window.location.origin : '')
    if (host) {
      applyAdvertiseHost(host)
    } else if (saved) {
      setOrigin(saved)
      const h = originHost(saved)
      if (h) setAdvertiseHost(h)
    }
  }, [])

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

  async function persistOriginsAndProbe() {
    const cdn = cdnOrigin.trim()
    await api.savePanelSettings({
      install_origin: origin.trim(),
      ...(cdn ? { snapshot_cdn_origin: cdn } : {}),
    })
    await loadSettings()
    const res = await api.setupCheck()
    const cdnCheck = (res.checks || []).find((c) => c.id === 'cdn')
    if (cdn && cdnCheck && !cdnCheck.ok) {
      setError(`cdn down  ${cdnCheck.detail || cdn}`)
    }
    await api.setupStage('server')
    setChecks(res.checks || [])
    setCheckReady(!!res.ready)
    setStep(2)
  }

  async function submitOrigin() {
    if (!origin.trim()) {
      setError('server required — host IP or DNS, not 127.0.0.1 if you run Docker')
      return
    }
    const serverHost = originHost(origin)
    if (isLoopbackHost(serverHost) && !isLoopbackHost(pageHost())) {
      setError('server is 127.0.0.1 — other Docker containers cannot reach it. Use the host IP or DNS.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const probe = await api.probeServer(origin.trim())
      if (!probe.ok) {
        setError(`server unreachable  ${probe.detail || origin}`)
        return
      }
      setApiOriginOverride(origin.trim())
      try {
        const st = await api.setupStatus()
        setHasAdmin(!st.needed)
      } catch {
        setHasAdmin(false)
      }
      setStep(1)
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

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
      await persistOriginsAndProbe()
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
  const originDocLines =
    step === 0
      ? [
          '// pick the server, then connect',
          '// docker: never 127.0.0.1',
          '// that is the container itself',
          '',
          `server = ${serverPreview}`,
          `cdn    = ${cdnPreview}`,
        ]
      : doc.lines

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
            <div className="setup-block" {...blockProps('setup.step.origin')}>
              <p className="setup-note">
                first the server, then the password. docker: 127.0.0.1 is this
                container — a node / CDN / agent in another container will not reach
                it. Put the Docker host IP or DNS and the published port (server
                :8094, cdn :8095).
              </p>
              <TextInput
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="host"
                placeholder="10.0.0.2 or solana.example"
                value={advertiseHost}
                onChange={(e) => applyAdvertiseHost(e.currentTarget.value.trim())}
              />
              <p className="setup-note">
                will be{' '}
                <span className="mono">{serverPreview}</span>
                {' · '}
                <span className="mono">{cdnPreview}</span>
              </p>
              {advertiseHost && isLoopbackHost(advertiseHost) ? (
                <p className="setup-err">
                  ! {advertiseHost} is loopback — only this process. Use the host IP
                  you SSH to, or a DNS name.
                </p>
              ) : null}
              <TextInput
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="server"
                placeholder={serverPreview}
                value={origin}
                onChange={(e) => {
                  const next = e.currentTarget.value.trim()
                  setOrigin(next)
                  const h = originHost(next)
                  if (h) setAdvertiseHost(h)
                }}
                required
              />
              <TextInput
                variant="unstyled"
                classNames={{ root: 'setup-field', input: 'setup-field__input', label: 'setup-field__label' }}
                label="cdn"
                placeholder={cdnPreview}
                value={cdnOrigin}
                onChange={(e) => setCdnOrigin(e.currentTarget.value.trim())}
              />
              <div className="setup-actions">
                <SetupCmd busy={busy} onClick={() => void submitOrigin()}>
                  connect
                </SetupCmd>
              </div>
            </div>
          )}

          {step === 1 && (
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
                <SetupCmd ghost onClick={() => setStep(0)}>
                  back
                </SetupCmd>
                <SetupCmd type="submit" busy={busy}>
                  continue
                </SetupCmd>
              </div>
            </form>
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
            {originDocLines.map((line, i) => (
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
          {settings && (step === 2 || step === 3) && (
            <div className="setup-tape">
              <ChannelLinks links={settings?.links} scripts={settings?.scripts} panelScripts={settings?.panel_scripts} />
            </div>
          )}
        </div>
      }
    />
  )
}
