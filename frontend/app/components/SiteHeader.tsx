import { Link, useLocation } from "react-router";
import type { Stats } from "../lib/api";

interface SiteHeaderProps {
  stats?: Stats;
}

const NUMBER_FORMAT = new Intl.NumberFormat("en-US");

export function SiteHeader({ stats }: SiteHeaderProps) {
  const { pathname } = useLocation();

  const isTransactions = pathname === "/";
  const isSetup = pathname.startsWith("/setup");
  const isPlaid = pathname.startsWith("/plaid-test");

  return (
    <header className="masthead">
      <div className="masthead-bar">
        <Link to="/" className="wordmark">
          <span className="wordmark-mark" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="22 7 13.5 15.5 8.5 10.5 2 17" />
              <polyline points="16 7 22 7 22 13" />
            </svg>
          </span>
          <h1 className="wordmark-text">Finance Aggregator</h1>
        </Link>

        <nav className="view-tabs" aria-label="Primary">
          <Link to="/" className={`view-tab${isTransactions ? " is-active" : ""}`}>
            Transactions
          </Link>
          <Link to="/setup" className={`view-tab${isSetup ? " is-active" : ""}`}>
            Setup
          </Link>
          <Link
            to="/plaid-test"
            className={`view-tab view-tab--quiet${isPlaid ? " is-active" : ""}`}
          >
            Plaid
          </Link>
        </nav>

        {stats && (
          <div className="masthead-stats">
            <span>
              <b>{NUMBER_FORMAT.format(stats.institutions)}</b> inst.
            </span>
            <span className="dot">·</span>
            <span>
              <b>{NUMBER_FORMAT.format(stats.accounts)}</b> acct.
            </span>
            <span className="dot">·</span>
            <span>
              <b>{NUMBER_FORMAT.format(stats.transactions)}</b> txns
            </span>
          </div>
        )}
      </div>
    </header>
  );
}
