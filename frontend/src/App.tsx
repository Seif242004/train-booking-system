import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { RequireAdmin, RequireAuth } from './auth/guards';
import { Layout } from './components/Layout';
import { Admin } from './pages/Admin';
import { Home } from './pages/Home';
import { Profile } from './pages/Profile';
import { Stations } from './pages/Stations';

export function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Home />} />
            {/* Everything below needs a signed-in user; /admin also needs the ADMIN role. */}
            <Route element={<RequireAuth />}>
              <Route path="profile" element={<Profile />} />
              <Route element={<RequireAdmin />}>
                <Route path="admin" element={<Admin />} />
                <Route path="admin/stations" element={<Stations />} />
              </Route>
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
