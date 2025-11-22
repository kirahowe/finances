import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { LoadingIndicator } from '../../app/components/LoadingIndicator';

describe('LoadingIndicator', () => {
  it('displays loading message when isLoading is true', () => {
    render(<LoadingIndicator isLoading={true} />);
    expect(screen.getByText(/loading/i)).toBeInTheDocument();
  });

  it('displays nothing when isLoading is false', () => {
    const { container } = render(<LoadingIndicator isLoading={false} />);
    expect(container.textContent).toBe('');
  });

  it('displays custom loading message when provided', () => {
    render(<LoadingIndicator isLoading={true} message="Loading transactions..." />);
    expect(screen.getByText('Loading transactions...')).toBeInTheDocument();
  });

  it('applies loading class for styling', () => {
    render(<LoadingIndicator isLoading={true} />);
    const loadingElement = screen.getByText(/loading/i);
    expect(loadingElement).toHaveClass('loading');
  });
});
