import type { Route } from "./+types/accounts";
import { api, type Account } from "../lib/api";

export function meta({}: Route.MetaArgs) {
  return [{ title: "Accounts - Finance Aggregator" }];
}

export async function loader(): Promise<{ accounts: Account[] }> {
  const accounts = await api.getAccounts();
  return { accounts };
}

export default function Accounts({ loaderData }: Route.ComponentProps) {
  const { accounts } = loaderData;

  return (
    <div className="container">
      <h1>Accounts</h1>

      <div className="card">
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
                <td>{account["account/name"]}</td>
                <td>{account["account/type"]}</td>
                <td>{account["account/currency"]}</td>
                <td>{account["account/external-id"] || "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="nav-links">
        <a href="/" className="button button-secondary">
          Back to Home
        </a>
      </div>
    </div>
  );
}
