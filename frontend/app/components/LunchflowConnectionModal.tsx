import { useEffect, useMemo, useState } from 'react';
import { api, type Account } from '../lib/api';
import {
  groupByInstitution,
  partitionConnected,
  type SelectableAccount,
} from '../lib/providerAccounts';

interface LunchflowConnectionModalProps {
  existingAccounts: Account[];
  onClose: () => void;
  onConnected: () => void;
}

const PROVIDER = 'lunchflow';

export function LunchflowConnectionModal({
  existingAccounts,
  onClose,
  onConnected,
}: LunchflowConnectionModalProps) {
  const [accounts, setAccounts] = useState<SelectableAccount[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getAvailableAccounts(PROVIDER)
      .then((available) => {
        if (cancelled) return;
        setAccounts(partitionConnected(available, existingAccounts));
      })
      .catch((err) => {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : 'Failed to load accounts');
      });
    return () => {
      cancelled = true;
    };
  }, [existingAccounts]);

  const groups = useMemo(
    () => (accounts ? groupByInstitution(accounts) : []),
    [accounts]
  );

  const toggle = (externalId: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(externalId)) next.delete(externalId);
      else next.add(externalId);
      return next;
    });
  };

  const handleConnect = async () => {
    if (selected.size === 0) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await api.syncProvider(PROVIDER, { accountIds: [...selected] });
      onConnected();
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Failed to import accounts');
      setSubmitting(false);
    }
  };

  const isLoading = accounts === null && loadError === null;
  const isEmpty = accounts !== null && accounts.length === 0;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal-content provider-connection-content"
        onClick={(e) => e.stopPropagation()}
      >
        <h2>Connect Lunchflow Accounts</h2>

        {isLoading && <p className="provider-connection-status">Loading accounts…</p>}

        {loadError && <div className="error-banner">{loadError}</div>}

        {isEmpty && (
          <p className="provider-connection-status">
            Lunchflow returned no accounts to import.
          </p>
        )}

        {accounts !== null && accounts.length > 0 && (
          <>
            <p className="provider-connection-hint">
              Choose which accounts to import. Already-connected accounts stay synced
              and can't be removed here.
            </p>
            <div className="provider-connection-scroll">
              {groups.map((group) => (
                <div key={group.institutionId} className="provider-institution">
                  <h3 className="provider-institution-name">
                    {group.institutionLogo && (
                      <img
                        className="provider-institution-logo"
                        src={group.institutionLogo}
                        alt=""
                      />
                    )}
                    {group.institutionName}
                  </h3>
                  <ul className="provider-account-list">
                    {group.accounts.map((account) => {
                      const id = account['external-id'];
                      const checked = account.connected || selected.has(id);
                      return (
                        <li key={id} className="provider-account-row">
                          <label className="provider-account-label">
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={account.connected || submitting}
                              onChange={() => toggle(id)}
                            />
                            <span className="provider-account-name">{account.name}</span>
                          </label>
                          {account.connected && (
                            <span className="provider-connected-tag">Connected</span>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))}
            </div>
          </>
        )}

        {submitError && <div className="error-banner">{submitError}</div>}

        <div className="form-actions">
          <button
            type="button"
            className="button button-secondary"
            onClick={onClose}
            disabled={submitting}
          >
            Cancel
          </button>
          <button
            type="button"
            className="button"
            onClick={handleConnect}
            disabled={selected.size === 0 || submitting}
          >
            {submitting
              ? 'Importing…'
              : `Import ${selected.size} account${selected.size === 1 ? '' : 's'}`}
          </button>
        </div>
      </div>
    </div>
  );
}
