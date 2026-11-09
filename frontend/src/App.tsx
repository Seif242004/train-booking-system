import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ToastProvider } from './components/Toasts';
import { RequireAdmin, RequireAuth } from './auth/guards';
import { AdminLayout } from './components/AdminLayout';
import { Layout } from './components/Layout';
import { ContactPage, HelpPage, PrivacyPage, RefundsPage, TermsPage } from './pages/InfoPages';
import { Landing } from './pages/Landing';
import { Admin } from './pages/Admin';
import { MyBookingsPage } from './pages/MyBookingsPage';
import { NotFound } from './pages/NotFound';
import { PaymentPage } from './pages/PaymentPage';
import { TicketPage } from './pages/TicketPage';
import { AccountPage } from './pages/AccountPage';
import { RouteFares } from './pages/RouteFares';
import { RouteList } from './pages/RouteList';
import { SearchTrips } from './pages/SearchTrips';
import { SeatMapPage } from './pages/SeatMapPage';
import { Stations } from './pages/Stations';
import { SupportPage } from './pages/SupportPage';
import { TrainDetail } from './pages/TrainDetail';
import { Trips } from './pages/Trips';
import { Trains } from './pages/Trains';

/**
 * The root component: provider stack plus the route table of the whole web app.
 * Nesting order matters: ErrorBoundary (last-resort crash screen) wraps ToastProvider (small messages), which wraps
 * AuthProvider (Keycloak session state), which wraps BrowserRouter (URL routing); each inner part may use the outer ones.
 * Route groups: public pages (landing and information pages), then RequireAuth (any signed-in user), then RequireAdmin
 * inside it (role ADMIN), whose pages are drawn inside AdminLayout. Layout is the shared header and footer.
 * The "*" route at the end is the 404 page. The guards only decide what is shown; the Gateway re-checks the token and the
 * role on every request.
 */
export function App() {
  return (
    <ErrorBoundary>
    <ToastProvider>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Landing />} />
            <Route path="help" element={<HelpPage />} />
            <Route path="refunds" element={<RefundsPage />} />
            <Route path="terms" element={<TermsPage />} />
            <Route path="privacy" element={<PrivacyPage />} />
            <Route path="contact" element={<ContactPage />} />
            {/* Everything below needs a signed-in user; /admin also needs the ADMIN role. */}
            <Route element={<RequireAuth />}>
              <Route path="account" element={<AccountPage />} />
              <Route path="profile" element={<Navigate to="/account" replace />} />
              <Route path="search" element={<SearchTrips />} />
              <Route path="trips/:id/seats" element={<SeatMapPage />} />
              <Route path="bookings" element={<MyBookingsPage />} />
              <Route path="bookings/:id/pay" element={<PaymentPage />} />
              <Route path="bookings/:id/ticket" element={<TicketPage />} />
              <Route element={<RequireAdmin />}>
                <Route element={<AdminLayout />}>
                <Route path="admin" element={<Admin />} />
                <Route path="admin/stations" element={<Stations />} />
                <Route path="admin/trains" element={<Trains />} />
                <Route path="admin/trains/:id" element={<TrainDetail />} />
                <Route path="admin/routes" element={<RouteList />} />
                <Route path="admin/trips" element={<Trips />} />
                <Route path="admin/support" element={<SupportPage />} />
                <Route path="admin/routes/:id/fares" element={<RouteFares />} />
                </Route>
              </Route>
            </Route>
            <Route path="*" element={<NotFound />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
    </ToastProvider>
    </ErrorBoundary>
  );
}
