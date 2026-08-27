import { Routes, Route, NavLink } from 'react-router-dom';
import DashboardPage from './pages/DashboardPage';
import CreatePackagePage from './pages/CreatePackagePage';
import PackageDetailPage from './pages/PackageDetailPage';
import ReviewPage from './pages/ReviewPage';
import VaultPage from './pages/VaultPage';

export default function App() {
  return (
    <div className="app-layout">
      <aside className="sidebar">
        <h2>SecureDoc</h2>
        <div className="demo-label">Local / Demo</div>
        <nav>
          <NavLink to="/" end>Packages</NavLink>
          <NavLink to="/packages/new">Create Package</NavLink>
          <NavLink to="/review">Review</NavLink>
          <NavLink to="/vault">Vault / Access</NavLink>
        </nav>
      </aside>
      <main className="main-content">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/packages/new" element={<CreatePackagePage />} />
          <Route path="/packages/:packageId" element={<PackageDetailPage />} />
          <Route path="/review" element={<ReviewPage />} />
          <Route path="/vault" element={<VaultPage />} />
        </Routes>
      </main>
    </div>
  );
}
