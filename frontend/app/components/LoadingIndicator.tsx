interface LoadingIndicatorProps {
  isLoading: boolean;
  message?: string;
}

export function LoadingIndicator({ isLoading, message = 'Loading...' }: LoadingIndicatorProps) {
  if (!isLoading) {
    return null;
  }

  return <div className="loading">{message}</div>;
}
