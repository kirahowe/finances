import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, useLoaderData, useSearchParams } from 'react-router';
import { MonthNavigator } from '../../app/components/MonthNavigator';
import { parseMonthParam, serializeMonth, type MonthState } from '../../app/lib/monthState';
import type { Transaction, Category } from '../../app/lib/api';

/**
 * Integration test for month navigation in the transactions view.
 *
 * This test verifies that when the user clicks the prev/next month buttons:
 * 1. The URL is updated with the new month parameter
 * 2. The loader is re-run with the new month parameter
 * 3. The transactions displayed are filtered by the selected month
 */
describe('Month Navigation Integration', () => {
  // Track loader calls to verify navigation triggers data refetch
  const loaderCallTracker = vi.fn();

  // Mock transactions from different months
  const january2025Transactions: Transaction[] = [
    {
      'db/id': 1,
      'transaction/posted-date': '2025-01-15',
      'transaction/payee': 'January Store',
      'transaction/description': 'January Purchase',
      'transaction/amount': -50.0,
      'transaction/category': null,
    },
    {
      'db/id': 2,
      'transaction/posted-date': '2025-01-20',
      'transaction/payee': 'January Shop',
      'transaction/description': 'Another January Purchase',
      'transaction/amount': -75.0,
      'transaction/category': null,
    },
  ];

  const december2024Transactions: Transaction[] = [
    {
      'db/id': 3,
      'transaction/posted-date': '2024-12-10',
      'transaction/payee': 'December Store',
      'transaction/description': 'December Purchase',
      'transaction/amount': -100.0,
      'transaction/category': null,
    },
  ];

  const february2025Transactions: Transaction[] = [
    {
      'db/id': 4,
      'transaction/posted-date': '2025-02-05',
      'transaction/payee': 'February Store',
      'transaction/description': 'February Purchase',
      'transaction/amount': -200.0,
      'transaction/category': null,
    },
  ];

  // Mock transaction database by month
  const transactionsByMonth: Record<string, Transaction[]> = {
    '2024-12': december2024Transactions,
    '2025-01': january2025Transactions,
    '2025-02': february2025Transactions,
  };

  beforeEach(() => {
    loaderCallTracker.mockClear();
  });

  // Test component that mimics the TransactionsSection behavior
  function TestTransactionsPage() {
    const { transactions, month } = useLoaderData() as { transactions: Transaction[]; month: string };
    const [searchParams, setSearchParams] = useSearchParams();
    const currentMonth = parseMonthParam(month);

    const handleMonthChange = (newMonth: MonthState) => {
      // This should trigger a loader re-run via React Router
      setSearchParams(prev => {
        const next = new URLSearchParams(prev);
        next.set('month', serializeMonth(newMonth));
        return next;
      });
    };

    return (
      <div>
        <div data-testid="current-month">{month}</div>
        <div data-testid="transaction-count">{transactions.length}</div>
        <MonthNavigator
          currentMonth={currentMonth}
          onMonthChange={handleMonthChange}
        />
        <table>
          <tbody>
            {transactions.map(tx => (
              <tr key={tx['db/id']} data-testid={`transaction-${tx['db/id']}`}>
                <td>{tx['transaction/payee']}</td>
                <td>{tx['transaction/posted-date']}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  // Loader that simulates the real loader behavior
  const createLoader = () => {
    return ({ request }: { request: Request }) => {
      const url = new URL(request.url);
      const monthParam = url.searchParams.get('month');

      // Parse month (defaults to current month if invalid)
      const monthState = parseMonthParam(monthParam);
      const month = serializeMonth(monthState);

      // Track loader calls for debugging
      loaderCallTracker({ month, originalParam: monthParam });

      // Return transactions for the requested month
      const transactions = transactionsByMonth[month] || [];
      return { transactions, month };
    };
  };

  const renderWithRouter = (initialUrl = '/?month=2025-01') => {
    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: <TestTransactionsPage />,
          loader: createLoader(),
        },
      ],
      {
        initialEntries: [initialUrl],
      }
    );
    return render(<RouterProvider router={router} />);
  };

  it('displays transactions for the initial month', async () => {
    renderWithRouter('/?month=2025-01');

    // Wait for loader to complete and render
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2025-01');
    });

    // Should show January 2025 transactions
    expect(screen.getByTestId('transaction-count')).toHaveTextContent('2');
    expect(screen.getByText('January Store')).toBeInTheDocument();
    expect(screen.getByText('January Shop')).toBeInTheDocument();
  });

  it('updates transactions when navigating to previous month', async () => {
    const user = userEvent.setup();
    renderWithRouter('/?month=2025-01');

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2025-01');
    });

    // Verify initial state
    expect(screen.getByText('January Store')).toBeInTheDocument();
    expect(loaderCallTracker).toHaveBeenCalledTimes(1);

    // Click previous month button
    const prevButton = screen.getByTitle('Previous month');
    await user.click(prevButton);

    // Should navigate to December 2024 and show December transactions
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2024-12');
    });

    // The loader should have been called again with the new month
    expect(loaderCallTracker).toHaveBeenCalledTimes(2);
    expect(loaderCallTracker).toHaveBeenLastCalledWith({
      month: '2024-12',
      originalParam: '2024-12',
    });

    // Should show December 2024 transactions, NOT January
    expect(screen.getByTestId('transaction-count')).toHaveTextContent('1');
    expect(screen.getByText('December Store')).toBeInTheDocument();
    expect(screen.queryByText('January Store')).not.toBeInTheDocument();
  });

  it('updates transactions when navigating to next month', async () => {
    const user = userEvent.setup();
    renderWithRouter('/?month=2025-01');

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2025-01');
    });

    // Click next month button
    const nextButton = screen.getByTitle('Next month');
    await user.click(nextButton);

    // Should navigate to February 2025 and show February transactions
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2025-02');
    });

    // Should show February 2025 transactions
    expect(screen.getByTestId('transaction-count')).toHaveTextContent('1');
    expect(screen.getByText('February Store')).toBeInTheDocument();
    expect(screen.queryByText('January Store')).not.toBeInTheDocument();
  });

  it('shows correct month display text after navigation', async () => {
    const user = userEvent.setup();
    renderWithRouter('/?month=2025-01');

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByText('January 2025')).toBeInTheDocument();
    });

    // Navigate to previous month
    const prevButton = screen.getByTitle('Previous month');
    await user.click(prevButton);

    // Should show December 2024 in the display
    await waitFor(() => {
      expect(screen.getByText('December 2024')).toBeInTheDocument();
    });
  });

  it('preserves month after multiple navigations', async () => {
    const user = userEvent.setup();
    renderWithRouter('/?month=2025-01');

    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2025-01');
    });

    // Navigate back twice (Jan -> Dec -> Nov)
    const prevButton = screen.getByTitle('Previous month');
    await user.click(prevButton);
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2024-12');
    });

    await user.click(prevButton);
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2024-11');
    });

    // Navigate forward once (Nov -> Dec)
    const nextButton = screen.getByTitle('Next month');
    await user.click(nextButton);
    await waitFor(() => {
      expect(screen.getByTestId('current-month')).toHaveTextContent('2024-12');
    });

    // Should show December transactions
    expect(screen.getByText('December Store')).toBeInTheDocument();
  });
});
