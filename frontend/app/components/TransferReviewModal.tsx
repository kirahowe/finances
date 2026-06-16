import { useEffect, useState } from 'react';
import { api, type TransferSuggestion } from '../lib/api';
import { Modal } from './Modal';
import { formatAmount, formatDate } from '../lib/format';
import '../styles/components/transfer-modal.css';

interface TransferReviewModalProps {
  onClose: () => void;
  // Refresh the list to reflect committed changes. Called on success AND on a
  // partial failure, so the table never diverges from what was actually written.
  onApplied: () => void;
}

type Decision = 'confirm' | 'skip' | 'reject';

export function TransferReviewModal({ onClose, onApplied }: TransferReviewModalProps) {
  const [suggestions, setSuggestions] = useState<TransferSuggestion[] | null>(null);
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [applied, setApplied] = useState<Set<number>>(new Set());

  useEffect(() => {
    let active = true;
    api
      .getTransferSuggestions()
      .then((s) => {
        if (active) {
          setSuggestions(s);
          setDecisions(s.map(() => 'confirm'));
        }
      })
      .catch((e) => {
        if (active) setLoadError(e instanceof Error ? e.message : 'Failed to load suggestions');
      });
    return () => {
      active = false;
    };
  }, []);

  const setDecision = (i: number, d: Decision) =>
    setDecisions((prev) => prev.map((p, idx) => (idx === i ? d : p)));

  const confirmCount = decisions.filter((d) => d === 'confirm').length;
  const hasRejections = decisions.includes('reject');

  const apply = async () => {
    if (!suggestions) return;
    setSubmitting(true);
    setError(null);
    let changed = false;
    // Carry forward what already committed so a retry after a partial failure
    // resumes instead of re-confirming already-linked pairs (which 409s).
    const done = new Set(applied);
    try {
      for (let i = 0; i < suggestions.length; i++) {
        if (done.has(i)) continue;
        const s = suggestions[i];
        if (decisions[i] === 'confirm') {
          await api.confirmTransfer(s.outflow['db/id'], s.inflow['db/id']);
          done.add(i);
          changed = true;
        } else if (decisions[i] === 'reject') {
          await api.rejectTransfer(s.outflow['db/id'], s.inflow['db/id']);
          done.add(i);
          changed = true;
        }
      }
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to apply changes');
      setSubmitting(false);
    } finally {
      setApplied(done);
      if (changed) onApplied();
    }
  };

  return (
    <Modal onClose={onClose} label="Review transfers" className="transfer-modal-content">
        <h2>Review transfers</h2>
        <p className="transfer-modal-hint">
          These pairs look like the same money moving between your accounts. Confirm the
          ones to link (and hide), or mark a pair as “not a transfer” so it isn’t suggested
          again.
        </p>

        {loadError && <div className="error-banner">{loadError}</div>}

        {!suggestions && !loadError && <div className="transfer-empty">Searching…</div>}

        {suggestions && suggestions.length === 0 && (
          <div className="transfer-empty">No matching transfers found.</div>
        )}

        {suggestions && suggestions.length > 0 && (
          <div className="transfer-suggestion-list">
            {suggestions.map((s, i) => {
              const decision = decisions[i];
              const days = s['day-diff'];
              return (
                <div
                  key={`${s.outflow['db/id']}-${s.inflow['db/id']}`}
                  className={`transfer-suggestion ${decision === 'reject' ? 'is-rejected' : ''}`}
                >
                  <input
                    type="checkbox"
                    className="transfer-suggestion-check"
                    aria-label="Link this transfer"
                    checked={decision === 'confirm'}
                    disabled={submitting || decision === 'reject'}
                    onChange={(e) => setDecision(i, e.target.checked ? 'confirm' : 'skip')}
                  />
                  <div className="transfer-suggestion-body">
                    <div className="transfer-suggestion-route">
                      <span>{s.outflow['transaction/account']?.['account/external-name'] ?? 'Unknown'}</span>
                      <span className="transfer-arrow" aria-hidden="true">→</span>
                      <span>{s.inflow['transaction/account']?.['account/external-name'] ?? 'Unknown'}</span>
                    </div>
                    <div className="transfer-suggestion-meta">
                      {[
                        s.outflow['transaction/posted-date'] &&
                          formatDate(s.outflow['transaction/posted-date']),
                        days > 0 && `${days} day${days === 1 ? '' : 's'} apart`,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </div>
                  </div>
                  <span className="numeric transfer-suggestion-amount">
                    {formatAmount(Math.abs(s.amount))}
                  </span>
                  <button
                    type="button"
                    className="transfer-reject-button"
                    disabled={submitting}
                    onClick={() => setDecision(i, decision === 'reject' ? 'skip' : 'reject')}
                  >
                    {decision === 'reject' ? 'Undo' : 'Not a transfer'}
                  </button>
                </div>
              );
            })}
          </div>
        )}

        {error && <div className="error-banner">{error}</div>}

        <div className="transfer-modal-actions">
          <button
            type="button"
            className="button button-secondary"
            onClick={onClose}
            disabled={submitting}
          >
            {suggestions && suggestions.length === 0 ? 'Close' : 'Cancel'}
          </button>
          {suggestions && suggestions.length > 0 && (
            <button
              type="button"
              className="button"
              onClick={apply}
              disabled={submitting || (confirmCount === 0 && !hasRejections)}
            >
              {submitting
                ? 'Applying…'
                : confirmCount === 0
                  ? 'Apply changes'
                  : `Link ${confirmCount} transfer${confirmCount === 1 ? '' : 's'}`}
            </button>
          )}
        </div>
    </Modal>
  );
}
