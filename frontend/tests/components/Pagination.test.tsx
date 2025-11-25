import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Pagination } from '../../app/components/Pagination';

describe('Pagination', () => {
  const defaultProps = {
    currentPage: 0,
    pageSize: 25 as const,
    totalItems: 100,
    onPageChange: vi.fn(),
    onPageSizeChange: vi.fn(),
  };

  it('renders page size buttons', () => {
    render(<Pagination {...defaultProps} />);

    expect(screen.getByRole('button', { name: '25' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '50' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '100' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '250' })).toBeInTheDocument();
  });

  it('highlights current page size', () => {
    render(<Pagination {...defaultProps} pageSize={50} />);

    const button50 = screen.getByRole('button', { name: '50' });
    expect(button50).toHaveClass('button-primary');
  });

  it('renders navigation buttons', () => {
    render(<Pagination {...defaultProps} />);

    expect(screen.getByTitle('First page')).toBeInTheDocument();
    expect(screen.getByTitle('Previous page')).toBeInTheDocument();
    expect(screen.getByTitle('Next page')).toBeInTheDocument();
    expect(screen.getByTitle('Last page')).toBeInTheDocument();
  });

  it('renders page number buttons', () => {
    render(<Pagination {...defaultProps} />);

    // With 100 items and 25 per page, we should have pages 1-4
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '2' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '4' })).toBeInTheDocument();
  });

  it('highlights current page', () => {
    render(<Pagination {...defaultProps} currentPage={2} />);

    const page3Button = screen.getByRole('button', { name: '3' });
    expect(page3Button).toHaveClass('button-primary');
  });

  it('disables first/previous buttons on first page', () => {
    render(<Pagination {...defaultProps} currentPage={0} />);

    expect(screen.getByTitle('First page')).toBeDisabled();
    expect(screen.getByTitle('Previous page')).toBeDisabled();
  });

  it('enables first/previous buttons on other pages', () => {
    render(<Pagination {...defaultProps} currentPage={1} />);

    expect(screen.getByTitle('First page')).not.toBeDisabled();
    expect(screen.getByTitle('Previous page')).not.toBeDisabled();
  });

  it('disables next/last buttons on last page', () => {
    render(<Pagination {...defaultProps} currentPage={3} />);

    expect(screen.getByTitle('Next page')).toBeDisabled();
    expect(screen.getByTitle('Last page')).toBeDisabled();
  });

  it('enables next/last buttons on other pages', () => {
    render(<Pagination {...defaultProps} currentPage={0} />);

    expect(screen.getByTitle('Next page')).not.toBeDisabled();
    expect(screen.getByTitle('Last page')).not.toBeDisabled();
  });

  it('calls onPageChange when clicking first button', () => {
    const onPageChange = vi.fn();
    render(<Pagination {...defaultProps} currentPage={2} onPageChange={onPageChange} />);

    fireEvent.click(screen.getByTitle('First page'));
    expect(onPageChange).toHaveBeenCalledWith(0);
  });

  it('calls onPageChange when clicking previous button', () => {
    const onPageChange = vi.fn();
    render(<Pagination {...defaultProps} currentPage={2} onPageChange={onPageChange} />);

    fireEvent.click(screen.getByTitle('Previous page'));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('calls onPageChange when clicking next button', () => {
    const onPageChange = vi.fn();
    render(<Pagination {...defaultProps} currentPage={1} onPageChange={onPageChange} />);

    fireEvent.click(screen.getByTitle('Next page'));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  it('calls onPageChange when clicking last button', () => {
    const onPageChange = vi.fn();
    render(<Pagination {...defaultProps} currentPage={0} onPageChange={onPageChange} />);

    fireEvent.click(screen.getByTitle('Last page'));
    expect(onPageChange).toHaveBeenCalledWith(3);
  });

  it('calls onPageChange when clicking page number', () => {
    const onPageChange = vi.fn();
    render(<Pagination {...defaultProps} onPageChange={onPageChange} />);

    fireEvent.click(screen.getByRole('button', { name: '3' }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  it('calls onPageSizeChange when clicking page size button', () => {
    const onPageSizeChange = vi.fn();
    render(<Pagination {...defaultProps} onPageSizeChange={onPageSizeChange} />);

    fireEvent.click(screen.getByRole('button', { name: '50' }));
    expect(onPageSizeChange).toHaveBeenCalledWith(50);
  });

  it('adjusts current page when changing page size would make it out of bounds', () => {
    const onPageChange = vi.fn();
    const onPageSizeChange = vi.fn();
    render(
      <Pagination
        {...defaultProps}
        currentPage={3}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    );

    // Changing to 50 per page means only 2 pages total
    // Current page 3 is out of bounds, should adjust
    fireEvent.click(screen.getByRole('button', { name: '50' }));
    expect(onPageSizeChange).toHaveBeenCalledWith(50);
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('shows sliding window of page numbers for many pages', () => {
    render(<Pagination {...defaultProps} currentPage={5} pageSize={25} totalItems={250} />);

    // Should show pages around current page
    expect(screen.getByRole('button', { name: '4' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '5' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '6' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '7' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '8' })).toBeInTheDocument();

    // Should not show first or last pages when in the middle
    expect(screen.queryByRole('button', { name: '1' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '10' })).not.toBeInTheDocument();
  });
});
