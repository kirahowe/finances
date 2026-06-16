import { useEffect, useState } from 'react';
import { api, type Transaction, type SuggestionTx } from '../lib/api';
import { Modal } from './Modal';
import { formatAmount, formatDate } from '../lib/format';
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

  // A transfer-categorized row is in one of two states: already linked to a
  // counterpart (show the partner + an Unmatch action) or open (search for a
  // counterpart to link). One modal covers both.
  const pair = transaction['transaction/transfer-pair'];
  const isMatched = !!pair;

  const txId = transaction['db/id'];
  useEffect(() => {
    if (isMatched) return;
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
  }, [txId, isMatched]);

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

  const unmatch = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.unmatchTransfer(transaction['db/id']);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to unmatch transfer');
      setSubmitting(false);
    }
  };

  return (
    <Modal
      onClose={onClose}
      label={isMatched ? 'Matched transfer' : 'Match transfer'}
      className="transfer-modal-content"
    >
        <h2>{isMatched ? 'Matched transfer' : 'Match transfer'}</h2>
        <p className="split-modal-sub">
          <span>{transaction['transaction/payee']}</span>
          <span className={`numeric ${transaction['transaction/amount'] >= 0 ? 'positive' : 'negative'}`}>
            {formatAmount(transaction['transaction/amount'])}
          </span>
        </p>

        {isMatched ? (
          <>
            <p className="transfer-modal-hint">
              Linked as a transfer with the matching transaction on another account.
            </p>

            <div className="transfer-suggestion-list">
              <div className="transfer-candidate is-static">
                <div className="transfer-suggestion-body">
                  <div className="transfer-suggestion-route">
                    <span>{pair['transaction/account']?.['account/external-name'] ?? 'Another account'}</span>
                  </div>
                  {pair['transaction/posted-date'] && (
                    <div className="transfer-suggestion-meta">{formatDate(pair['transaction/posted-date'])}</div>
                  )}
                </div>
                <span
                  className={`numeric transfer-suggestion-amount ${pair['transaction/amount'] >= 0 ? 'positive' : 'negative'}`}
                >
                  {formatAmount(pair['transaction/amount'])}
                </span>
              </div>
            </div>

            {error && <div className="error-banner">{error}</div>}

            <div className="transfer-modal-actions">
              <button type="button" className="button button-danger" onClick={unmatch} disabled={submitting}>
                Unmatch transfer
              </button>
              <button type="button" className="button button-secondary" onClick={onClose} disabled={submitting}>
                Close
              </button>
            </div>
          </>
        ) : (
          <>
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
          </>
        )}
    </Modal>
  );
}
