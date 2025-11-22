import type { Route } from "./+types/accounts";
import { api, type Account } from "../lib/api";
import { useNavigation, useRevalidator } from "react-router";
import { LoadingIndicator } from "../components/LoadingIndicator";

export function meta({}: Route.MetaArgs) {
  return [{ title: "Accounts - Finance Aggregator" }];
}

export async function loader(): Promise<{ accounts: Account[] }> {
  const accounts = await api.getAccounts();
  return { accounts };
}

export default function Accounts({ loaderData }: Route.ComponentProps) {
  const { accounts } = loaderData;
  const navigation = useNavigation();
  const revalidator = useRevalidator();

  const isLoading = navigation.state === 'loading';

  return (
    <div className="container">
      <h1>Accounts</h1>

      <LoadingIndicator isLoading={isLoading} message="Loading accounts..." />

      {!isLoading && (
        <div className="card">
          <div className="card-header">
            <h2>All Accounts</h2>
            <button className="button" onClick={() => revalidator.revalidate()}>
              Refresh
            </button>
          </div>

          <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Currency</th>
              <th>External ID</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((account) => (
              <tr key={account["db/id"]}>
                <td>{account["account/external-name"]}</td>
                <td>{account["account/type"] || "—"}</td>
                <td>{account["account/currency"]}</td>
                <td>{account["account/external-id"] || "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}

      <div className="nav-links">
        <a href="/" className="button button-secondary">
          Back to Home
        </a>
      </div>
    </div>
  );
}
