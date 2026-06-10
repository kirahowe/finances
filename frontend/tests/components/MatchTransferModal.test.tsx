import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MatchTransferModal } from '../../app/components/MatchTransferModal';
import { api, type Transaction, type SuggestionTx } from '../../app/lib/api';

vi.mock('../../app/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../app/lib/api')>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getMatchCandidates: vi.fn(),
      confirmTransfer: vi.fn(),
    },
  };
});

function tx(overrides: Partial<Transaction> & Pick<Transaction, 'db/id' | 'transaction/amount'>): Transaction {
  return {
    'transaction/payee': 'Transfer',
    'transaction/posted-date': '2025-01-10',
    ...overrides,
  } as Transaction;
}

const candidate: SuggestionTx = {
  'db/id': 2,
  'transaction/amount': 500,
  'transaction/payee': 'Other Leg',
  'transaction/posted-date': '2025-01-11',
  'transaction/account': { 'db/id': 11, 'account/external-name': 'Savings' },
};

describe('MatchTransferModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('links an outflow source to the candidate as (outflow, inflow)', async () => {
    const user = userEvent.setup();
    vi.mocked(api.getMatchCandidates).mockResolvedValue([{ ...candidate, 'transaction/amount': 500 }]);
    vi.mocked(api.confirmTransfer).mockResolvedValue();
    const onSaved = vi.fn();

    render(
      <MatchTransferModal
        transaction={tx({ 'db/id': 1, 'transaction/amount': -500 })}
        onClose={vi.fn()}
        onSaved={onSaved}
      />
    );

    await user.click(await screen.findByRole('button', { name: /Other Leg/ }));

    // Source is the outflow (negative), candidate is the inflow.
    expect(api.confirmTransfer).toHaveBeenCalledWith(1, 2);
    expect(onSaved).toHaveBeenCalled();
  });

  it('links an inflow source by flipping the direction (candidate is the outflow)', async () => {
    const user = userEvent.setup();
    vi.mocked(api.getMatchCandidates).mockResolvedValue([{ ...candidate, 'transaction/amount': -500 }]);
    vi.mocked(api.confirmTransfer).mockResolvedValue();

    render(
      <MatchTransferModal
        transaction={tx({ 'db/id': 1, 'transaction/amount': 500 })}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    await user.click(await screen.findByRole('button', { name: /Other Leg/ }));

    // Source is the inflow (positive), so the candidate is passed as the outflow.
    expect(api.confirmTransfer).toHaveBeenCalledWith(2, 1);
  });

  it('shows an empty state when no counterpart exists', async () => {
    vi.mocked(api.getMatchCandidates).mockResolvedValue([]);

    render(
      <MatchTransferModal
        transaction={tx({ 'db/id': 1, 'transaction/amount': -500 })}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    expect(await screen.findByText(/No matching transaction found/)).toBeInTheDocument();
  });

  it('surfaces an error and does not call onSaved when linking fails', async () => {
    const user = userEvent.setup();
    vi.mocked(api.getMatchCandidates).mockResolvedValue([candidate]);
    vi.mocked(api.confirmTransfer).mockRejectedValue(new Error('already part of a transfer'));
    const onSaved = vi.fn();

    render(
      <MatchTransferModal
        transaction={tx({ 'db/id': 1, 'transaction/amount': -500 })}
        onClose={vi.fn()}
        onSaved={onSaved}
      />
    );

    await user.click(await screen.findByRole('button', { name: /Other Leg/ }));

    expect(await screen.findByText('already part of a transfer')).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
  });
});
