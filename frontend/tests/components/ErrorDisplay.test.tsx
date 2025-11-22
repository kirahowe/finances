import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ErrorDisplay } from '../../app/components/ErrorDisplay';

describe('ErrorDisplay', () => {
  it('displays error message when error is provided', () => {
    render(<ErrorDisplay error="Something went wrong" onDismiss={vi.fn()} />);
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
  });

  it('displays nothing when error is null', () => {
    const { container } = render(<ErrorDisplay error={null} onDismiss={vi.fn()} />);
    expect(container.textContent).toBe('');
  });

  it('displays "Error:" prefix in error message', () => {
    render(<ErrorDisplay error="Network failure" onDismiss={vi.fn()} />);
    expect(screen.getByText(/error:/i)).toBeInTheDocument();
  });

  it('has dismiss button when error is shown', () => {
    render(<ErrorDisplay error="Test error" onDismiss={vi.fn()} />);
    const dismissButton = screen.getByRole('button', { name: /dismiss/i });
    expect(dismissButton).toBeInTheDocument();
  });

  it('calls onDismiss when dismiss button is clicked', async () => {
    const user = userEvent.setup();
    const onDismiss = vi.fn();

    render(<ErrorDisplay error="Test error" onDismiss={onDismiss} />);

    const dismissButton = screen.getByRole('button', { name: /dismiss/i });
    await user.click(dismissButton);

    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it('applies error class for styling', () => {
    render(<ErrorDisplay error="Test error" onDismiss={vi.fn()} />);
    const errorElement = screen.getByText(/error:/i).parentElement;
    expect(errorElement).toHaveClass('error');
  });
});
