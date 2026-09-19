import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AdminLayout } from './layouts/AdminLayout'
import { AuthProvider, RequireAuth, useAuth } from './lib/auth'
import { BankReconciliationPage } from './pages/BankReconciliationPage'
import { LoginPage } from './pages/LoginPage'
import { MyAccountPage } from './pages/MyAccountPage'
import { PaymentAlertPage } from './pages/PaymentAlertPage'

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } },
})

/** `/` goes wherever the user belongs; the Laravel welcome page had no equivalent worth keeping. */
function Home() {
  const auth = useAuth()
  if (auth.status === 'loading') return null
  if (auth.status === 'anonymous') return <Navigate to="/login" replace />
  return <Navigate to={auth.user.isAdmin ? '/payment-alert' : '/mi-cuenta'} replace />
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<LoginPage />} />

            <Route element={<RequireAuth admin />}>
              <Route element={<AdminLayout />}>
                <Route path="/payment-alert" element={<PaymentAlertPage />} />
                <Route path="/conciliacion-bancaria" element={<BankReconciliationPage />} />
              </Route>
            </Route>

            <Route element={<RequireAuth />}>
              <Route path="/mi-cuenta" element={<MyAccountPage />} />
            </Route>

            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}
