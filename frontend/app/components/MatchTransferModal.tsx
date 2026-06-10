import { useEffect, useState } from 'react';
import { api, type Transaction, type SuggestionTx } from '../lib/api';
import { formatAmount, formatDate } from '../lib/format';
import { useBodyScrollLock } from '../lib/useBodyScrollLock';
import '../styles/components/transfer-modal.css';

interface MatchTransferModalProps {
  transaction: Transaction;
  onClose: () => void;
  onSaved: () => void;
}

export function MatchTransferModal({ transaction, onClose, onSaved }: MatchTransferModalProps) {
  const [candidates, setCandidates] = useState<SuggestionTx[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useBodyScrollLock();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const txId = transaction['db/id'];
  useEffect(() => {
    let active = true;
    api
      .getMatchCandidates(txId)
      .then((c) => {
        if (active) setCandidates(c);
      })
      .catch((e) => {
        if (active) setLoadError(e instanceof Error ? e.message : 'Failed to load candidates');
      });
    return () => {
      active = false;
    };
  }, [txId]);

  const isOutflow = transaction['transaction/amount'] < 0;

  const link = async (candidate: SuggestionTx) => {
    setSubmitting(true);
    setError(null);
    try {
      const outflowId = isOutflow ? transaction['db/id'] : candidate['db/id'];
      const inflowId = isOutflow ? candidate['db/id'] : transaction['db/id'];
      await api.confirmTransfer(outflowId, inflowId);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to link transfer');
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal-content transfer-modal-content"
        role="dialog"
        aria-modal="true"
        aria-label="Match transfer"
        onClick={(e) => e.stopPropagation()}
      >
        <h2>Match transfer</h2>
        <p className="split-modal-sub">
          <span>{transaction['transaction/payee']}</span>
          <span className={`numeric ${transaction['transaction/amount'] >= 0 ? 'positive' : 'negative'}`}>
            {formatAmount(transaction['transaction/amount'])}
          </span>
        </p>
        <p className="transfer-modal-hint">
          Pick the matching transaction on another account to link them as a transfer.
        </p>

        {loadError && <div className="error-banner">{loadError}</div>}
        {!candidates && !loadError && <div className="transfer-empty">Searching…</div>}

        {candidates && candidates.length === 0 && (
          <div className="transfer-empty">
            No matching transaction found on another account. This transfer stays visible —
            you can match it later once the other side is imported.
          </div>
        )}

        {candidates && candidates.length > 0 && (
          <div className="transfer-suggestion-list">
            {candidates.map((c) => (
              <button
                key={c['db/id']}
                type="button"
                className="transfer-candidate"
                disabled={submitting}
                onClick={() => link(c)}
              >
                <div className="transfer-suggestion-body">
                  <div className="transfer-suggestion-route">
                    <span>{c['transaction/account']?.['account/external-name'] ?? 'Unknown'}</span>
                  </div>
                  <div className="transfer-suggestion-meta">
                    {c['transaction/payee']}
                    {c['transaction/posted-date'] && ` · ${formatDate(c['transaction/posted-date'])}`}
                  </div>
                </div>
                <span
                  className={`numeric transfer-suggestion-amount ${c['transaction/amount'] >= 0 ? 'positive' : 'negative'}`}
                >
                  {formatAmount(c['transaction/amount'])}
                </span>
              </button>
            ))}
          </div>
        )}

        {error && <div className="error-banner">{error}</div>}

        <div className="transfer-modal-actions">
          <button type="button" className="button button-secondary" onClick={onClose} disabled={submitting}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
