import { Route, Routes } from 'react-router-dom'
import { RequireAuth } from './auth/RequireAuth'
import { Layout } from './components/Layout'
import { CheckDetailPage } from './pages/CheckDetailPage'
import { ChecksPage } from './pages/ChecksPage'
import { LandingPage } from './pages/LandingPage'
import { NotFoundPage } from './pages/NotFoundPage'

// 화면명세서 §0: S-01 /  ·  S-02 /checks  ·  S-03 /checks/:checkId
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/checks" element={<ChecksPage />} />
          <Route path="/checks/:checkId" element={<CheckDetailPage />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
