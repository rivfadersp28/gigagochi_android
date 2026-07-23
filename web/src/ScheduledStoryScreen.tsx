import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type PropsWithChildren,
} from "react";
import { BundledStoryMedia } from "./BundledStoryMedia";
import { ContextualStoryBack } from "./ContextualStoryBack";
import type { ScheduledStoryScreenState } from "./EventStoryTypes";
import { GlyphRevealText } from "./GlyphRevealText";
import { motionConfig } from "./motionConfig";
import { storyRevealDuration } from "./storyMotion";
import { usePressMotion } from "./usePressMotion";
import "./eventStory.css";

export type StoryScrollTarget = "top" | "answers" | "finishAction";

interface ScheduledStoryScreenProps {
  state: ScheduledStoryScreenState;
  reducedMotion: boolean;
  foreground?: boolean;
  forcePoster?: boolean;
  scrollTarget?: StoryScrollTarget;
  choicePending?: boolean;
  onChoice(choice: string): void;
  onRetry?(): void;
  onFinish(): void;
  onBack(): void;
}

function StoryReferenceFrame({ children }: PropsWithChildren) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [metrics, setMetrics] = useState({ scale: 1, height: 874 });
  useLayoutEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const update = () => {
      const bounds = host.getBoundingClientRect();
      const width = bounds.width > 0 ? bounds.width : 402;
      const height = bounds.height > 0 ? bounds.height : 874;
      const scale = width / 402;
      setMetrics({ scale, height: Math.max(874, height / scale) });
    };
    update();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(update);
    observer?.observe(host);
    window.addEventListener("resize", update);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", update);
    };
  }, []);
  return (
    <div ref={hostRef} className="scheduled-story__reference-host">
      <div
        className="scheduled-story__reference-plane"
        style={{
          "--story-reference-scale": String(metrics.scale),
          height: `${metrics.height}px`,
        } as CSSProperties}
      >
        {children}
      </div>
    </div>
  );
}

function StoryGlassButton({
  label,
  index,
  reducedMotion,
  entrance,
  disabled = false,
  finish = false,
  onClick,
}: {
  label: string;
  index: number;
  reducedMotion: boolean;
  entrance: boolean;
  disabled?: boolean;
  finish?: boolean;
  onClick(): void;
}) {
  const press = usePressMotion<HTMLButtonElement>({
    disabled,
    reducedMotion,
    kind: "storyTween",
  });
  return (
    <div
      className={[
        "scheduled-story__glass-answer-shell",
        entrance ? "scheduled-story__glass-answer--entrance" : "",
        reducedMotion ? "scheduled-story__glass-answer--reduced" : "",
      ].filter(Boolean).join(" ")}
      style={{
        "--story-answer-delay":
          `${index * motionConfig.story.answerEntranceStaggerMillis}ms`,
        "--story-answer-tilt": `${index % 2 === 0 ? -2 : 2}deg`,
      } as CSSProperties}
    >
      <button
        ref={press.ref}
        className={finish
          ? "scheduled-story__glass-answer scheduled-story__glass-answer--finish"
          : "scheduled-story__glass-answer"}
        type="button"
        aria-label={label}
        disabled={disabled}
        data-press-phase={press.phase}
        onPointerDown={press.onPointerDown}
        onPointerUp={press.onPointerUp}
        onPointerCancel={press.onPointerCancel}
        onPointerLeave={press.onPointerLeave}
        onKeyDown={press.onKeyDown}
        onKeyUp={press.onKeyUp}
        onBlur={press.onBlur}
        onClick={onClick}
      >
        {label}
      </button>
    </div>
  );
}

function StoryThinkingIndicator({ reducedMotion }: { reducedMotion: boolean }) {
  const [frame, setFrame] = useState(0);
  useEffect(() => {
    if (reducedMotion) {
      setFrame(0);
      return;
    }
    const timer = window.setInterval(
      () => setFrame((current) => (current + 1) % 3),
      motionConfig.shared.thinkingFrameIntervalMillis,
    );
    return () => window.clearInterval(timer);
  }, [reducedMotion]);

  return (
    <div
      className="scheduled-story__thinking"
      role="status"
      aria-label="Персонаж думает"
      data-frame={frame + 1}
    >
      <img src={`/res/thinking_frame_${frame + 1}.png`} alt="" />
    </div>
  );
}

function StoryCopy({
  state,
  reducedMotion,
}: Pick<ScheduledStoryScreenProps, "state" | "reducedMotion">) {
  const result = state.result;
  const paragraphs = result
    ? result.paragraphs
    : state.story.questionParagraphs;
  const starts = paragraphs.map((_, index) => (
    paragraphs.slice(0, index).reduce((sum, paragraph) => sum + paragraph.length, 0)
  ));
  const storyCharacterCount = paragraphs.reduce((sum, paragraph) => sum + paragraph.length, 0);
  const storyDuration = storyRevealDuration(storyCharacterCount);
  const reactionDuration = storyRevealDuration(result?.reaction.length ?? 0);
  return (
    <section
      className="scheduled-story__copy"
      aria-label={result ? "Результат выбора" : "История и вопрос"}
    >
      {paragraphs.map((paragraph, index) => (
        <GlyphRevealText
          key={`${index}-${paragraph}`}
          text={paragraph}
          startIndex={starts[index]}
          baseDelayMillis={0}
          reducedMotion={reducedMotion}
        />
      ))}
      {result ? (
        <>
          <div className="scheduled-story__reaction">
            <GlyphRevealText
              text={result.reaction}
              startIndex={0}
              baseDelayMillis={storyDuration}
              muted
              reducedMotion={reducedMotion}
            />
          </div>
          <div
            className={reducedMotion
              ? "scheduled-story__experience scheduled-story__experience--reduced"
              : "scheduled-story__experience"}
            aria-label={`Получено ${result.experienceGained} единиц опыта`}
            style={{
              "--story-experience-delay": `${storyDuration + reactionDuration}ms`,
            } as CSSProperties}
          >
            <strong>+{result.experienceGained}</strong>
            <img src="/res/xp_coin.svg" alt="" />
          </div>
        </>
      ) : null}
    </section>
  );
}

export function ScheduledStoryScreen({
  state,
  reducedMotion,
  foreground = true,
  forcePoster = false,
  scrollTarget = "top",
  choicePending = false,
  onChoice,
  onRetry = () => undefined,
  onFinish,
  onBack,
}: ScheduledStoryScreenProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const result = state.result;
  const isResult = state.phase === "result" || result !== null;
  const mainRef = useRef<HTMLElement>(null);
  const previousChoicePendingRef = useRef(choicePending);
  const previousResultRef = useRef(isResult);
  const fallbackPoster = isResult
    ? "/res/onboarding_bat_success.png"
    : "/res/onboarding_bat_situation.png";
  const fallbackVideo = isResult
    ? "/assets/media/onboarding-bat-success.mp4"
    : "/assets/media/onboarding-bat-situation.mp4";
  const posterRef = result?.imageRef ??
    state.story.imageRef ??
    fallbackPoster;
  const videoRef = result?.videoRef ??
    state.story.videoRef ??
    fallbackVideo;
  const enabledChoice = state.story.enabledChoice?.trim() || null;
  const retryChoice = state.phase === "retryable" ? state.pendingChoice : null;

  useLayoutEffect(() => {
    const scroll = scrollRef.current;
    if (!scroll) return;
    scroll.scrollTop = scrollTarget === "top" ? 0 : scroll.scrollHeight;
  }, [scrollTarget, state.phase]);

  useLayoutEffect(() => {
    const wasPending = previousChoicePendingRef.current;
    const wasResult = previousResultRef.current;
    previousChoicePendingRef.current = choicePending;
    previousResultRef.current = isResult;

    if (!wasPending && choicePending) {
      mainRef.current?.focus({ preventScroll: true });
      return;
    }
    if (!choicePending && !wasResult && isResult) {
      scrollRef.current
        ?.querySelector<HTMLButtonElement>(".scheduled-story__glass-answer--finish")
        ?.focus({ preventScroll: true });
      return;
    }
    if (wasPending && !choicePending && retryChoice) {
      Array.from(
        scrollRef.current?.querySelectorAll<HTMLButtonElement>(
          ".scheduled-story__glass-answer",
        ) ?? [],
      ).find((button) => button.textContent === retryChoice)
        ?.focus({ preventScroll: true });
    }
  }, [choicePending, isResult, retryChoice]);

  return (
    <main
      ref={mainRef}
      className={reducedMotion
        ? "scheduled-story scheduled-story--reduced-motion"
        : "scheduled-story"}
      tabIndex={-1}
    >
      <StoryReferenceFrame>
        <div className="scheduled-story__backdrop" aria-hidden="true">
          {fallbackPoster ? <img src={fallbackPoster} alt="" /> : null}
        </div>
        <div
          ref={scrollRef}
          className={choicePending
            ? "scheduled-story__scroll scheduled-story__scroll--pending"
            : "scheduled-story__scroll"}
          role="region"
          aria-label="Интерактивное путешествие"
          tabIndex={0}
          aria-hidden={choicePending || undefined}
          inert={choicePending || undefined}
        >
          <BundledStoryMedia
            posterRef={posterRef}
            videoRef={videoRef}
            playVideo
            reducedMotion={reducedMotion}
            foreground={foreground}
            forcePoster={forcePoster}
            description={isResult ? `Итог события: ${state.story.title}` : `Видео события: ${state.story.title}`}
            className="scheduled-story__media"
          />
          <StoryCopy state={state} reducedMotion={reducedMotion} />
          {!result ? (
            <section className="scheduled-story__answers" aria-label="Варианты ответа">
              {state.story.choices.slice(0, 4).map((choice, index) => (
                <StoryGlassButton
                  key={choice}
                  label={choice}
                  index={index}
                  entrance
                  reducedMotion={reducedMotion}
                  disabled={retryChoice !== null
                    ? choice !== retryChoice
                    : enabledChoice !== null && choice !== enabledChoice}
                  onClick={() => retryChoice !== null ? onRetry() : onChoice(choice)}
                />
              ))}
            </section>
          ) : (
            <div className="scheduled-story__finish-row">
              <StoryGlassButton
                label="Завершить"
                index={0}
                entrance={false}
                finish
                reducedMotion={reducedMotion}
                onClick={onFinish}
              />
            </div>
          )}
        </div>
        {state.phase === "retryable" && state.error ? (
          <p className="scheduled-story__error" role="status" aria-label={state.error}>
            {state.error}
          </p>
        ) : null}
        {choicePending ? <StoryThinkingIndicator reducedMotion={reducedMotion} /> : null}
      </StoryReferenceFrame>
      <ContextualStoryBack onClick={onBack} />
    </main>
  );
}
