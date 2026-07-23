import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import type { FirstSessionSnapshot } from "./contracts";
import { motionConfig } from "./motionConfig";
import { createBrowserSpeechSequence } from "./speechSequence";

export interface FirstSessionPresentation {
  message: string;
  actionVisible: boolean;
  canAdvance: boolean;
  advance(): void;
  markRevealComplete(): void;
}

export function useFirstSessionPresentation(
  session: FirstSessionSnapshot | null,
  fallbackMessage: string,
  reducedMotion: boolean,
  presentationActive = true,
): FirstSessionPresentation {
  const presentationId = session
    ? `${session.stage}:${session.messagePortions.join("\u0000")}`
    : `ordinary:${fallbackMessage}`;
  const [presentation, setPresentation] = useState({
    id: presentationId,
    portionIndex: 0,
    revealComplete: reducedMotion,
  });
  const current = presentation.id === presentationId
    ? presentation
    : { id: presentationId, portionIndex: 0, revealComplete: reducedMotion };

  useEffect(() => {
    setPresentation({
      id: presentationId,
      portionIndex: 0,
      revealComplete: reducedMotion,
    });
  }, [presentationId]);

  const portions = session?.messagePortions.length
    ? session.messagePortions
    : [fallbackMessage];
  const boundedIndex = Math.min(current.portionIndex, portions.length - 1);
  const hasNext = boundedIndex + 1 < portions.length;
  const advance = useCallback(() => {
    if (!presentationActive || !hasNext) return;
    setPresentation((latest) => ({
      id: presentationId,
      portionIndex: Math.min(
        latest.id === presentationId ? latest.portionIndex + 1 : 1,
        portions.length - 1,
      ),
      revealComplete: reducedMotion,
    }));
  }, [hasNext, portions.length, presentationActive, presentationId, reducedMotion]);
  const markRevealComplete = useCallback(() => {
    setPresentation((latest) => latest.id === presentationId
      ? { ...latest, revealComplete: true }
      : { id: presentationId, portionIndex: 0, revealComplete: true });
  }, [presentationId]);

  useEffect(() => {
    if (!reducedMotion) return;
    setPresentation((latest) => latest.id === presentationId
      ? { ...latest, revealComplete: true }
      : latest);
  }, [presentationId, reducedMotion]);

  useEffect(() => {
    if (!presentationActive || !session || !current.revealComplete || !hasNext) return;
    const timer = window.setTimeout(() => {
      setPresentation((latest) => ({
        id: presentationId,
        portionIndex: Math.min(
          latest.id === presentationId ? latest.portionIndex + 1 : 1,
          portions.length - 1,
        ),
        revealComplete: reducedMotion,
      }));
    }, motionConfig.dialogue.autoAdvanceDelayMillis);
    return () => window.clearTimeout(timer);
  }, [
    current.revealComplete,
    hasNext,
    portions.length,
    presentationActive,
    presentationId,
    reducedMotion,
    session,
  ]);

  return {
    message: portions[boundedIndex] ?? fallbackMessage,
    actionVisible: !session || (current.revealComplete && !hasNext),
    canAdvance: hasNext,
    advance,
    markRevealComplete,
  };
}

interface AnimatedDialogueTextProps {
  message: string;
  reducedMotion: boolean;
  canAdvance: boolean;
  onRevealComplete(): void;
  onAdvance(): void;
  className?: string;
  animateEntrance?: boolean;
  active?: boolean;
}

export interface AnimatedDialogueTextHandle {
  stopSpeech(): void;
}

export const AnimatedDialogueText = forwardRef<AnimatedDialogueTextHandle, AnimatedDialogueTextProps>(function AnimatedDialogueText({
  message,
  reducedMotion,
  canAdvance,
  onRevealComplete,
  onAdvance,
  className,
  animateEntrance = true,
  active = true,
}: AnimatedDialogueTextProps, ref) {
  const speechRef = useRef<ReturnType<typeof createBrowserSpeechSequence>>(null);
  const speechInitializedRef = useRef(false);
  const ensureSpeech = useCallback(() => {
    if (!speechInitializedRef.current) {
      speechInitializedRef.current = true;
      speechRef.current = createBrowserSpeechSequence();
    }
    return speechRef.current;
  }, []);
  useImperativeHandle(ref, () => ({ stopSpeech: () => speechRef.current?.stop() }), []);
  const shouldAnimate = animateEntrance && !reducedMotion;
  const duration = useMemo(() => {
    const units = Math.min(
      motionConfig.dialogue.maxAnimatedUnits,
      [...message].filter((character) => !/\s/u.test(character)).length,
    );
    return motionConfig.dialogue.unitDurationMillis +
      Math.max(0, units - 1) * motionConfig.dialogue.unitStaggerMillis;
  }, [message]);
  const revealProgressRef = useRef<{
    message: string;
    callback: () => void;
    shouldAnimate: boolean;
    elapsedMillis: number;
    startedAtMillis: number | null;
    completed: boolean;
  }>({
    message,
    callback: onRevealComplete,
    shouldAnimate,
    elapsedMillis: 0,
    startedAtMillis: null,
    completed: false,
  });
  const revealProgress = revealProgressRef.current;
  if (
    revealProgress.message !== message ||
    revealProgress.callback !== onRevealComplete ||
    revealProgress.shouldAnimate !== shouldAnimate
  ) {
    revealProgressRef.current = {
      message,
      callback: onRevealComplete,
      shouldAnimate,
      elapsedMillis: 0,
      startedAtMillis: null,
      completed: false,
    };
  }

  useEffect(() => {
    const progress = revealProgressRef.current;
    if (!active || progress.completed) return;
    if (!shouldAnimate || !message) {
      progress.completed = true;
      onRevealComplete();
      return;
    }
    const remainingMillis = Math.max(0, duration - progress.elapsedMillis);
    if (remainingMillis === 0) {
      progress.completed = true;
      onRevealComplete();
      return;
    }
    const startedAtMillis = performance.now();
    progress.startedAtMillis = startedAtMillis;
    const timer = window.setTimeout(() => {
      if (revealProgressRef.current !== progress || progress.completed) return;
      progress.elapsedMillis = duration;
      progress.startedAtMillis = null;
      progress.completed = true;
      onRevealComplete();
    }, remainingMillis);
    return () => {
      window.clearTimeout(timer);
      if (
        revealProgressRef.current === progress &&
        progress.startedAtMillis === startedAtMillis &&
        !progress.completed
      ) {
        progress.elapsedMillis = Math.min(
          duration,
          progress.elapsedMillis + Math.max(0, performance.now() - startedAtMillis),
        );
        progress.startedAtMillis = null;
      }
    };
  }, [active, duration, message, onRevealComplete, shouldAnimate]);

  useEffect(() => {
    if (!active || !shouldAnimate || !message) return;
    const speech = ensureSpeech();
    if (!speech) return;
    const progress = revealProgressRef.current;
    const remainingMillis = Math.max(0, duration - progress.elapsedMillis);
    if (remainingMillis === 0) return;
    speech.start(remainingMillis);
    return () => speech.stop();
  }, [active, duration, ensureSpeech, message, shouldAnimate]);

  useEffect(() => () => speechRef.current?.stop(), []);

  const renderMessage = (content: ReactNode, staticMotion: boolean) => {
    const classes = [
      "pet-message",
      "pet-message--animated",
      staticMotion ? "pet-message--static" : "",
      canAdvance ? "pet-message--interactive" : "",
      !active ? "pet-message--paused" : "",
      className ?? "",
    ].filter(Boolean).join(" ");
    if (!canAdvance) {
      return (
        <div className={classes} role="status" aria-live="polite">
          {content}
        </div>
      );
    }
    return (
      <button
        type="button"
        className={classes}
        onClick={() => {
          speechRef.current?.stop();
          onAdvance();
        }}
        aria-live="polite"
      >
        {content}
      </button>
    );
  };

  if (!shouldAnimate) {
    return renderMessage(
      <span className="pet-message__line">{message}</span>,
      true,
    );
  }

  let unitIndex = 0;
  return renderMessage(
      <span className="pet-message__line" key={message}>
        {[...message].map((character, index) => {
          if (/\s/u.test(character)) return character;
          const animated = unitIndex < motionConfig.dialogue.maxAnimatedUnits;
          const delay = animated
            ? unitIndex * motionConfig.dialogue.unitStaggerMillis
            : duration;
          unitIndex += 1;
          return (
            <span
              key={`${index}-${character}`}
              className={animated ? "pet-message__unit" : "pet-message__unit pet-message__unit--tail"}
              style={{
                "--unit-delay": `${delay}ms`,
                "--unit-duration": `${motionConfig.dialogue.unitDurationMillis}ms`,
              } as CSSProperties}
            >
              {character}
            </span>
          );
        })}
      </span>,
      false,
  );
});
