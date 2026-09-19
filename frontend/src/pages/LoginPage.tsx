import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

/**
 * Email and password. No registration and no password reset, deliberately: accounts come from the
 * production user table. Where to land after login is decided here from `isAdmin`, which the API
 * reports rather than performs — a single-page app does its own navigation.
 */
export function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [remember, setRemember] = useState(false)
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({})
  const [busy, setBusy] = useState(false)

  if (auth.status === 'authenticated') {
    return <Navigate to={auth.user.isAdmin ? '/payment-alert' : '/mi-cuenta'} replace />
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErrors({})
    setBusy(true)
    try {
      const user = await auth.login(email, password, remember)
      const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname
      navigate(from ?? (user.isAdmin ? '/payment-alert' : '/mi-cuenta'), { replace: true })
    } catch (err) {
      if (err instanceof ApiError) {
        setErrors({ email: err.for('email'), password: err.for('password') })
      } else {
        setErrors({ email: 'Ocurrió un error. Intente nuevamente.' })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="container py-5">
      <div className="row justify-content-center">
        <div className="col-md-6 col-lg-5">
          <div className="card">
            <div className="card-header"><strong>Iniciar sesión</strong></div>
            <div className="card-body">
              <form onSubmit={submit} noValidate>
                <div className="form-group">
                  <label htmlFor="login-email">Correo electrónico</label>
                  <input
                    id="login-email" type="email" autoComplete="username"
                    className={`form-control ${errors.email ? 'is-invalid' : ''}`}
                    value={email} onChange={(e) => setEmail(e.target.value)}
                  />
                  {errors.email && <div className="invalid-feedback d-block">{errors.email}</div>}
                </div>
                <div className="form-group">
                  <label htmlFor="login-password">Contraseña</label>
                  <input
                    id="login-password" type="password" autoComplete="current-password"
                    className={`form-control ${errors.password ? 'is-invalid' : ''}`}
                    value={password} onChange={(e) => setPassword(e.target.value)}
                  />
                  {errors.password && <div className="invalid-feedback d-block">{errors.password}</div>}
                </div>
                <div className="form-group form-check">
                  <input
                    id="login-remember" type="checkbox" className="form-check-input"
                    checked={remember} onChange={(e) => setRemember(e.target.checked)}
                  />
                  <label className="form-check-label" htmlFor="login-remember">Recordarme</label>
                </div>
                <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
                  {busy ? 'Ingresando…' : 'Ingresar'}
                </button>
              </form>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
