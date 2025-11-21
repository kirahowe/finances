import type { Route } from "./+types/home";
import { api, type Stats } from "../lib/api";
import "../styles/pages/dashboard.css";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Finance Aggregator" },
    { name: "description", content: "Personal finance aggregation tool" },
  ];
}

export async function loader(): Promise<{ stats: Stats }> {
  const stats = await api.getStats();
  return { stats };
}

export default function Home({ loaderData }: Route.ComponentProps) {
  const { stats } = loaderData;

  return (
    <div className="container">
      <h1>Finance Aggregator</h1>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-value">{stats.institutions}</div>
          <div className="stat-label">Institutions</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.accounts}</div>
          <div className="stat-label">Accounts</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.transactions}</div>
          <div className="stat-label">Transactions</div>
        </div>
      </div>

      <nav className="card">
        <h2>Navigate</h2>
        <div className="nav-links">
          <a href="/categories" className="button">Categories</a>
          <a href="/accounts" className="button">Accounts</a>
          <a href="/transactions" className="button">Transactions</a>
        </div>
      </nav>
    </div>
  );
}
