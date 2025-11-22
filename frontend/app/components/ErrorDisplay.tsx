interface ErrorDisplayProps {
  error: string | null;
  onDismiss: () => void;
}

export function ErrorDisplay({ error, onDismiss }: ErrorDisplayProps) {
  if (!error) {
    return null;
  }

  return (
    <div className="error">
      <strong>Error: </strong>{error}
      <button
        onClick={onDismiss}
        style={{ marginLeft: '10px' }}
      >
        Dismiss
      </button>
    </div>
  );
}
