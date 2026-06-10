import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransferReviewModal } from '../../app/components/TransferReviewModal';
import { api, type TransferSuggestion } from '../../app/lib/api';

vi.mock('../../app/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../app/lib/api')>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getTransferSuggestions: vi.fn(),
      confirmTransfer: vi.fn(),
      rejectTransfer: vi.fn(),
    },
  };
});

const suggestion: TransferSuggestion = {
  outflow: {
    'db/id': 1,
    'transaction/amount': -100,
    'transaction/posted-date': '2025-01-10',
    'transaction/account': { 'db/id': 10, 'account/external-name': 'Checking' },
  },
  inflow: {
    'db/id': 2,
    'transaction/amount': 100,
    'transaction/posted-date': '2025-01-11',
    'transaction/account': { 'db/id': 11, 'account/external-name': 'Savings' },
  },
  amount: 100,
  'day-diff': 1,
};

describe('TransferReviewModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('confirms checked suggestions on apply', async () => {
    const user = userEvent.setup();
    vi.mocked(api.getTransferSuggestions).mockResolvedValue([suggestion]);
    vi.mocked(api.confirmTransfer).mockResolvedValue();
    const onApplied = vi.fn();

    render(<TransferReviewModal onClose={vi.fn()} onApplied={onApplied} />);

    expect(await screen.findByText('Checking')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Link 1 transfer/ }));

    expect(api.confirmTransfer).toHaveBeenCalledWith(1, 2);
    expect(onApplied).toHaveBeenCalled();
  });

  it('records a rejection instead of linking', async () => {
    const user = userEvent.setup();
    vi.mocked(api.getTransferSuggestions).mockResolvedValue([suggestion]);
    vi.mocked(api.rejectTransfer).mockResolvedValue();

    render(<TransferReviewModal onClose={vi.fn()} onApplied={vi.fn()} />);

    await screen.findByText('Checking');
    await user.click(screen.getByRole('button', { name: 'Not a transfer' }));
    // With nothing left to confirm, the primary button stops claiming to "link"
    // and becomes a neutral apply action.
    await user.click(screen.getByRole('button', { name: 'Apply changes' }));

    expect(api.rejectTransfer).toHaveBeenCalledWith(1, 2);
    expect(api.confirmTransfer).not.toHaveBeenCalled();
  });

  it('shows an empty state when nothing is found', async () => {
    vi.mocked(api.getTransferSuggestions).mockResolvedValue([]);
    render(<TransferReviewModal onClose={vi.fn()} onApplied={vi.fn()} />);
    expect(await screen.findByText('No matching transfers found.')).toBeInTheDocument();
  });

  it('revalidates after a partial failure and keeps the modal open with an error', async () => {
    const user = userEvent.setup();
    const second: TransferSuggestion = {
      outflow: {
        'db/id': 3,
        'transaction/amount': -50,
        'transaction/posted-date': '2025-01-12',
        'transaction/account': { 'db/id': 12, 'account/external-name': 'Visa' },
      },
      inflow: {
        'db/id': 4,
        'transaction/amount': 50,
        'transaction/posted-date': '2025-01-12',
        'transaction/account': { 'db/id': 13, 'account/external-name': 'Chequing' },
      },
      amount: 50,
      'day-diff': 0,
    };
    vi.mocked(api.getTransferSuggestions).mockResolvedValue([suggestion, second]);
    vi.mocked(api.confirmTransfer)
      .mockResolvedValueOnce() // first pair commits
      .mockRejectedValueOnce(new Error('already part of a transfer')); // second fails
    const onApplied = vi.fn();
    const onClose = vi.fn();

    render(<TransferReviewModal onClose={onClose} onApplied={onApplied} />);

    await screen.findByText('Checking');
    await user.click(screen.getByRole('button', { name: /Link 2 transfers/ }));

    // The committed pair must trigger a refresh even though a later pair failed.
    expect(onApplied).toHaveBeenCalled();
    // On failure the modal stays open and surfaces the real error.
    expect(onClose).not.toHaveBeenCalled();
    expect(await screen.findByText('already part of a transfer')).toBeInTheDocument();
  });

  it('resumes from where it failed and does not re-confirm committed pairs on retry', async () => {
    const user = userEvent.setup();
    const second: TransferSuggestion = {
      outflow: {
        'db/id': 3,
        'transaction/amount': -50,
        'transaction/posted-date': '2025-01-12',
        'transaction/account': { 'db/id': 12, 'account/external-name': 'Visa' },
      },
      inflow: {
        'db/id': 4,
        'transaction/amount': 50,
        'transaction/posted-date': '2025-01-12',
        'transaction/account': { 'db/id': 13, 'account/external-name': 'Chequing' },
      },
      amount: 50,
      'day-diff': 0,
    };
    vi.mocked(api.getTransferSuggestions).mockResolvedValue([suggestion, second]);
    vi.mocked(api.confirmTransfer)
      .mockResolvedValueOnce() // first pair commits
      .mockRejectedValueOnce(new Error('boom')) // second pair fails mid-loop
      .mockResolvedValueOnce(); // retry of the second pair succeeds

    render(<TransferReviewModal onClose={vi.fn()} onApplied={vi.fn()} />);

    await screen.findByText('Checking');
    await user.click(screen.getByRole('button', { name: /Link 2 transfers/ }));

    // First attempt surfaces the failure and keeps the modal open.
    await screen.findByText('boom');

    // The apply (primary) button is the one without the secondary modifier.
    const buttons = screen.getAllByRole('button');
    const applyButton = buttons.find(
      (b) =>
        b.className.includes('button') &&
        !b.className.includes('button-secondary') &&
        /^(Link|Applying)/.test(b.textContent ?? '')
    )!;
    await user.click(applyButton);

    // Pair 1/2 already committed, so the retry must NOT re-confirm it; only the
    // previously-failed pair 3/4 is retried.
    expect(api.confirmTransfer).toHaveBeenCalledTimes(3);
    expect(api.confirmTransfer).toHaveBeenNthCalledWith(1, 1, 2);
    expect(api.confirmTransfer).toHaveBeenNthCalledWith(2, 3, 4);
    expect(api.confirmTransfer).toHaveBeenNthCalledWith(3, 3, 4);
  });
});
