interface ContextualStoryBackProps {
  onClick(): void;
}

export function ContextualStoryBack({ onClick }: ContextualStoryBackProps) {
  return (
    <button
      className="event-story-back"
      type="button"
      aria-label="Назад"
      onClick={onClick}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20Z" />
      </svg>
    </button>
  );
}
