import { TextInput } from '@mantine/core'
import { useState } from 'react'
import { api, type AuthStatus } from '../api'
import { navigate } from '../lib/router'
import { blockProps } from '../lib/blockId'
import { SetupCmd, SetupShell } from '../components/SetupShell'

const RPCNODE = 'https://rpcnode.dev'

const FIELD = {
  root: 'setup-field',
  input: 'setup-field__input',
  label: 'setup-field__label',
}

type LoginPageProps = {
  onAuthed: (auth: AuthStatus) => void
}

export function LoginPage({ onAuthed }: LoginPageProps) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const res = await api.login(username.trim(), password)
      onAuthed({
        ok: true,
        authenticated: true,
        user: res.user || username.trim(),
      })
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <SetupShell
      block="login"
      title="login"
      index="session"
      status="panel.auth  ·  sign in"
      left={
        <>
          <ol className="setup-rail">
            <li>
              <button type="button" className="setup-rail__item is-now">
                <span className="setup-rail__n">01</span>
                <span className="setup-rail__file">login</span>
                <span className="setup-rail__cur" aria-hidden />
              </button>
            </li>
          </ol>
          {error ? <p className="setup-err">! {error}</p> : null}
          <form className="setup-block" {...blockProps('login.form')} onSubmit={(e) => void submit(e)}>
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
              autoComplete="current-password"
              required
            />
            <div className="setup-actions">
              <SetupCmd type="submit" busy={busy}>
                continue
              </SetupCmd>
            </div>
            <p className="setup-note">
              first start?{' '}
              <button type="button" className="setup-link" onClick={() => navigate({ name: 'setup' })}>
                create admin
              </button>
              {'  ·  '}
              <a className="setup-link" href={RPCNODE} target="_blank" rel="noopener noreferrer">
                rpcnode.dev
              </a>
            </p>
          </form>
        </>
      }
      right={
        <div className="setup-doc">
          <div className="setup-doc__head">
            <span>// login</span>
            <span>cookie, not the agent token</span>
          </div>
          <pre className="setup-doc__src">
            <span>// local control panel</span>
            <span>// AGENT_API_TOKEN stays on the host</span>
            <span> </span>
            <span>fun login() {'{'}</span>
            <span>    verify(user, pass)</span>
            <span>{'}'}</span>
          </pre>
        </div>
      }
    />
  )
}
