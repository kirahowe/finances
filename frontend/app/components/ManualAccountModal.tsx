import { useState } from "react";
import { useFetcher } from "react-router";

interface ManualAccountModalProps {
  onClose: () => void;
}

export function ManualAccountModal({ onClose }: ManualAccountModalProps) {
  const [name, setName] = useState("");
  const [institutionName, setInstitutionName] = useState("");
  const [currency, setCurrency] = useState("USD");
  const fetcher = useFetcher();

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    fetcher.submit(
      {
        intent: "create-manual-account",
        name,
        institutionName,
        currency,
      },
      { method: "post" }
    );
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <h2>Create Manual Account</h2>
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="name">
              Account Name
            </label>
            <input
              className="form-input"
              type="text"
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g., TD Chequing, Cash"
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="institutionName">
              Institution Name
            </label>
            <input
              className="form-input"
              type="text"
              id="institutionName"
              value={institutionName}
              onChange={(e) => setInstitutionName(e.target.value)}
              placeholder="e.g., TD Bank, Cash"
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="currency">
              Currency
            </label>
            <select
              className="form-select"
              id="currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              required
            >
              <option value="USD">USD</option>
              <option value="CAD">CAD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
            </select>
          </div>

          <div className="form-actions">
            <button type="button" className="button button-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="button">
              Create
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
