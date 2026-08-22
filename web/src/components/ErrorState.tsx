interface ErrorStateProps {
  message: string;
  onRetry: () => void;
}

export function ErrorState({ message, onRetry }: ErrorStateProps) {
  return (
    <div className="state-panel state-panel--error" role="alert">
      <p className="state-panel__title">Couldn&apos;t reach the recommendation engine</p>
      <p className="state-panel__detail">{message}</p>
      <button type="button" className="state-panel__retry" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}
