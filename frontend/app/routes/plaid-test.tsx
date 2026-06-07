import { useState } from "react";
import type { Route } from "./+types/plaid-test";
import { usePlaidLink } from "react-plaid-link";
import type { PlaidLinkOnSuccess, PlaidLinkOnExit, PlaidLinkOnEvent } from "react-plaid-link";
import { api } from "../lib/api";
import "../styles/pages/plaid-test.css";

interface PlaidAccount {
  id: string;
  name: string;
  mask: string | null;
  type: string;
  subtype: string;
}

interface PlaidMetadata {
  institution?: {
    name: string;
    institution_id: string;
  };
  accounts: PlaidAccount[];
  link_session_id: string;
}

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Plaid Test - Finance Aggregator" },
    { name: "description", content: "Test Plaid integration" },
  ];
}

export async function loader({}: Route.LoaderArgs) {
  // Fetch link token on page load
  try {
    const linkTokenData = await api.createPlaidLinkToken();
    return { linkToken: linkTokenData.linkToken, error: null };
  } catch (error) {
    return {
      linkToken: null,
      error: error instanceof Error ? error.message : "Failed to create link token"
    };
  }
}

export default function PlaidTest({ loaderData }: Route.ComponentProps) {
  const { linkToken: initialLinkToken, error: loaderError } = loaderData;
  const [linkToken, setLinkToken] = useState<string | null>(initialLinkToken);
  const [error, setError] = useState<string | null>(loaderError);
  const [status, setStatus] = useState<string>("");
  const [exchangeResult, setExchangeResult] = useState<unknown>(null);
  const [selectedAccounts, setSelectedAccounts] = useState<PlaidAccount[]>([]);
  const [accounts, setAccounts] = useState<unknown>(null);
  const [transactions, setTransactions] = useState<unknown>(null);
  const [isLinked, setIsLinked] = useState(false);

  const handlePlaidLinkSuccess: PlaidLinkOnSuccess = async (publicToken, metadata) => {
    setStatus("Exchanging public token for access token...");
    console.log("Plaid Link success", { publicToken, metadata });

    // Extract selected account IDs from metadata
    const accountIds = metadata.accounts.map(account => account.id);
    console.log("Selected account IDs:", accountIds);
    console.log("Selected accounts:", metadata.accounts);

    // Store selected accounts for display
    setSelectedAccounts(metadata.accounts);

    try {
      const result = await api.exchangePlaidToken(publicToken, accountIds);
      setExchangeResult(result);
      setStatus("Successfully linked account!");
      setIsLinked(true);
      setError(null);
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to exchange token";
      setError(errorMsg);
      setStatus("");
      console.error("Exchange token error:", err);
    }
  };

  const handlePlaidLinkExit: PlaidLinkOnExit = (err, metadata) => {
    console.log("Plaid Link exit", { err, metadata });
    if (err) {
      setError("Plaid Link exited with error");
      setStatus("");
    }
  };

  const handlePlaidLinkEvent: PlaidLinkOnEvent = (eventName, metadata) => {
    console.log("Plaid Link event", { eventName, metadata });
  };

  const { open, ready } = usePlaidLink({
    token: linkToken,
    onSuccess: handlePlaidLinkSuccess,
    onExit: handlePlaidLinkExit,
    onEvent: handlePlaidLinkEvent,
  });

  const fetchAccounts = async () => {
    if (!isLinked) {
      setError("Please link an account first");
      return;
    }

    setStatus("Fetching accounts...");
    setError(null);

    try {
      const result = await api.getPlaidAccounts();
      setAccounts(result);
      setStatus("Accounts fetched successfully!");
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to fetch accounts";
      setError(errorMsg);
      setStatus("");
      console.error("Fetch accounts error:", err);
    }
  };

  const fetchTransactions = async () => {
    if (!isLinked) {
      setError("Please link an account first");
      return;
    }

    setStatus("Fetching transactions...");
    setError(null);

    // Default to last 30 days
    const endDate = new Date().toISOString().split('T')[0];
    const startDate = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

    try {
      const result = await api.getPlaidTransactions(startDate, endDate);
      setTransactions(result);
      setStatus("Transactions fetched successfully!");
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to fetch transactions";
      setError(errorMsg);
      setStatus("");
      console.error("Fetch transactions error:", err);
    }
  };

  const refreshLinkToken = async () => {
    setStatus("Creating new link token...");
    setError(null);

    try {
      const linkTokenData = await api.createPlaidLinkToken();
      setLinkToken(linkTokenData.linkToken);
      setStatus("Link token refreshed!");
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to create link token";
      setError(errorMsg);
      setStatus("");
      console.error("Refresh link token error:", err);
    }
  };

  return (
    <div className="container">
      <h1>Plaid Integration Test</h1>

      {error && (
        <div className="error-banner">
          <strong>Error:</strong> {error}
        </div>
      )}

      {status && (
        <div className="status-banner">
          {status}
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2>OAuth Flow</h2>
        </div>

        <div className="button-group">
          <button
            className="button button-primary"
            onClick={() => open()}
            disabled={!ready}
          >
            {isLinked ? "Link Another Account" : "Open Plaid Link"}
          </button>
          <button
            className="button button-secondary"
            onClick={refreshLinkToken}
          >
            Refresh Link Token
          </button>
        </div>

        {linkToken && (
          <div className="response-section">
            <h3>Link Token</h3>
            <pre className="json-display">{linkToken}</pre>
          </div>
        )}

        {selectedAccounts.length > 0 && (
          <div className="response-section">
            <h3>Selected Accounts</h3>
            <p className="summary">{selectedAccounts.length} account(s) selected</p>
            <ul className="selected-accounts-list">
              {selectedAccounts.map((account) => (
                <li key={account.id}>
                  <strong>{account.name}</strong>
                  {account.mask && ` (...${account.mask})`}
                  {' - '}
                  <span className="account-mask">{account.subtype || account.type}</span>
                </li>
              ))}
            </ul>
            <details>
              <summary>View Account Details</summary>
              <pre className="json-display">{JSON.stringify(selectedAccounts, null, 2)}</pre>
            </details>
          </div>
        )}

        {!!exchangeResult && (
          <div className="response-section">
            <h3>Exchange Result</h3>
            <p className="summary">Access token obtained and stored in database</p>
            <details>
              <summary>View Raw Response</summary>
              <pre className="json-display">{JSON.stringify(exchangeResult, null, 2)}</pre>
            </details>
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Data Fetching</h2>
        </div>

        <div className="button-group">
          <button
            className="button"
            onClick={fetchAccounts}
            disabled={!isLinked}
          >
            Fetch Accounts
          </button>
          <button
            className="button"
            onClick={fetchTransactions}
            disabled={!isLinked}
          >
            Fetch Transactions (Last 30 Days)
          </button>
        </div>

        {!!accounts && (
          <div className="response-section">
            <h3>Accounts</h3>
            <p className="summary">
              {Array.isArray(accounts) ? `${accounts.length} account(s) found` : "Accounts data"}
            </p>
            <details>
              <summary>View Raw Response</summary>
              <pre className="json-display">{JSON.stringify(accounts, null, 2)}</pre>
            </details>
          </div>
        )}

        {!!transactions && (
          <div className="response-section">
            <h3>Transactions</h3>
            <p className="summary">
              {Array.isArray(transactions) ? `${transactions.length} transaction(s) found` : "Transactions data"}
            </p>
            <details>
              <summary>View Raw Response</summary>
              <pre className="json-display">{JSON.stringify(transactions, null, 2)}</pre>
            </details>
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Instructions</h2>
        </div>
        <ol className="instructions-list">
          <li>Click "Open Plaid Link" to start the OAuth flow</li>
          <li>In the Plaid Link modal, select "Continue" and choose a test bank</li>
          <li>For Sandbox testing, use:
            <ul>
              <li>Username: <code>user_good</code></li>
              <li>Password: <code>pass_good</code></li>
            </ul>
          </li>
          <li>
            <strong>Account Selection:</strong> Select which accounts to sync using the
            checkboxes in the Plaid Link flow. The selected accounts will be displayed
            above after linking.
          </li>
          <li>After successful linking, click "Fetch Accounts" to test account retrieval</li>
          <li>Click "Fetch Transactions" to test transaction retrieval</li>
        </ol>
        <p className="plaid-config-note">
          <strong>Note:</strong> To enable multiple account selection, configure the
          Account Select setting in your{' '}
          <a href="https://dashboard.plaid.com/link" target="_blank" rel="noopener noreferrer">
            Plaid Dashboard
          </a>
          {' '}under Link Customization.
        </p>
      </div>
    </div>
  );
}
