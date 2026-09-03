import { TextInput } from '@mantine/core'
import { useState } from 'react'
import { api } from '../api'
import { navigate } from '../lib/router'
import { SetupCmd, SetupShell } from '../components/SetupShell'
import { blockProps } from '../lib/blockId'

const FIELD = {
  root: 'setup-field',
  input: 'setup-field__input',
  label: 'setup-field__label',
}

export function SetupPasswordPage() {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
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
      navigate({ name: 'dashboard' })
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <SetupShell
      block="setup.password"
      title="setup"
      index="01 / 01  admin"
      status="panel.install  ·  pending"
      left={
        <>
          <ol className="setup-rail">
            <li>
              <button type="button" className="setup-rail__item is-now">
                <span className="setup-rail__n">01</span>
                <span className="setup-rail__file">admin</span>
                <span className="setup-rail__cur" aria-hidden />
              </button>
            </li>
          </ol>
          {error ? <p className="setup-err">! {error}</p> : null}
          <form className="setup-block" {...blockProps('setup.password.form')} onSubmit={(e) => void submit(e)}>
            <TextInput
              variant="unstyled"
              classNames={FIELD}
              label="user"
              value={username}
              onChange={(e) => setUsername(e.currentTarget.value)}
              autoComplete="username"
              required
            />
            <TextInput
              type="password"
              variant="unstyled"
              classNames={FIELD}
              label="pass"
              value={password}
              onChange={(e) => setPassword(e.currentTarget.value)}
              autoComplete="new-password"
              required
            />
            <TextInput
              type="password"
              variant="unstyled"
              classNames={FIELD}
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
            <p className="setup-note">
              already configured?{' '}
              <button type="button" className="setup-link" onClick={() => navigate({ name: 'login' })}>
                sign in
              </button>
            </p>
          </form>
        </>
      }
      right={
        <div className="setup-doc">
          <div className="setup-doc__head">
            <span>// admin</span>
            <span>human, not the agent</span>
          </div>
          <pre className="setup-doc__src">
            <span>// password is for people in the UI</span>
            <span>// agents use AGENT_API_TOKEN</span>
            <span>// host install prints the token</span>
            <span> </span>
            <span>fun setup() {'{'}</span>
            <span>    createAdmin(user, pass)</span>
            <span>{'}'}</span>
          </pre>
        </div>
      }
    />
  )
}
