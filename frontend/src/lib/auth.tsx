/**
 * Who is logged in, for the whole app. A server-rendered page always knew; a single-page app has to
 * ask on load (`GET /api/auth/me`) and remember the answer.
 *
 * This is NOT the security boundary. Every endpoint re-checks the session and the admin role; this
 * only decides which page to show. The Laravel middleware redirected a non-admin away from the staff
 * page; here that redirect is `RequireAuth`'s job, because the API answers 403 instead.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { api, ApiError, primeCsrf } from './api'

export type Me = {
  id: number
  name: string
  email: string
  isAdmin: boolean
  empresaId: number | null
}

type AuthState =
  | { status: 'loading'; user: null }
  | { status: 'anonymous'; user: null }
  | { status: 'authenticated'; user: Me }

type AuthContextValue = AuthState & {
  login: (email: string, password: string, remember: boolean) => Promise<Me>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null })

  useEffect(() => {
    let cancelled = false
    api.get<Me>('/api/auth/me')
      .then((user) => { if (!cancelled) setState({ status: 'authenticated', user }) })
      .catch(() => { if (!cancelled) setState({ status: 'anonymous', user: null }) })
    return () => { cancelled = true }
  }, [])

  const login = useCallback(async (email: string, password: string, remember: boolean) => {
    await primeCsrf()
    const user = await api.post<Me>('/api/auth/login', { email, password, remember })
    setState({ status: 'authenticated', user })
    return user
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.post<void>('/api/auth/logout')
    } catch (e) {
      // A dead session is already logged out.
      if (!(e instanceof ApiError && e.status === 401)) throw e
    }
    setState({ status: 'anonymous', user: null })
  }, [])

  const value = useMemo<AuthContextValue>(() => ({ ...state, login, logout }), [state, login, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}

/**
 * Route guard. Guests go to /login; a non-admin on an admin route goes to their own account — the
 * client-side mirror of the Laravel `auth`/`admin` middleware.
 */
export function RequireAuth({ admin = false }: { admin?: boolean }) {
  const auth = useAuth()
  const location = useLocation()

  if (auth.status === 'loading') {
    return <p className="p-4 text-muted">Cargando…</p>
  }
  if (auth.status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  if (admin && !auth.user.isAdmin) {
    return <Navigate to="/mi-cuenta" replace />
  }
  return <Outlet />
}
