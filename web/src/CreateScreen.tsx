import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type RefObject,
} from "react";
import type {
  CreateSnapshot,
  JsonValue,
  ProductCommandType,
  WebFeedbackKind,
} from "./contracts";
import { CreationMosaic } from "./CreationMosaic";
import { motionConfig } from "./motionConfig";
import { ReferencePlane } from "./ReferencePlane";
import { SegmentedCreationMedia } from "./SegmentedCreationMedia";

type Dispatch = (type: ProductCommandType, payload?: JsonValue) => Promise<void>;

interface CreateScreenProps {
  state: CreateSnapshot;
  busy: boolean;
  reducedMotion: boolean;
  customOpen: boolean;
  customValue: string;
  onOpenCustom(): void;
  onCloseCustom(): void;
  onUpdateCustom(value: string): void;
  feedback(kind: WebFeedbackKind): void;
  dispatch: Dispatch;
}

const optionTilts = [-2, 2, -2, 2];

interface TiltedCreationButtonProps {
  label: string;
  index: number;
  delayMillis?: number;
  animateEntrance?: boolean;
  buttonRef?: RefObject<HTMLButtonElement | null>;
  onClick(): void;
}

function TiltedCreationButton({
  label,
  index,
  delayMillis = 0,
  animateEntrance = true,
  buttonRef,
  onClick,
}: TiltedCreationButtonProps) {
  return (
    <div
      className={animateEntrance
        ? "creation-glass-option-frame"
        : "creation-glass-option-frame creation-glass-option-frame--static"}
      style={{
        "--tilt": `${optionTilts[index] ?? (index % 2 === 0 ? -2 : 2)}deg`,
        "--entrance-delay": `${delayMillis}ms`,
      } as CSSProperties}
    >
      <button
        ref={buttonRef}
        className="creation-glass-option"
        type="button"
        onClick={onClick}
      >
        {label}
      </button>
    </div>
  );
}

function BackIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20Z" />
    </svg>
  );
}

function ThinkingFrames({ reducedMotion }: { reducedMotion: boolean }) {
  const [frame, setFrame] = useState(0);
  useEffect(() => {
    if (reducedMotion) return;
    const timer = window.setInterval(
      () => setFrame((current) => (current + 1) % 3),
      motionConfig.shared.thinkingFrameIntervalMillis,
    );
    return () => window.clearInterval(timer);
  }, [reducedMotion]);
  return (
    <img
      className="creation-thinking"
      src={`/res/thinking_frame_${reducedMotion ? 1 : frame + 1}.png`}
      alt=""
    />
  );
}

function PetCreatingStage({ reducedMotion, dispatch }: {
  reducedMotion: boolean;
  dispatch: Dispatch;
}) {
  const completedRef = useRef(false);
  const inFlightRef = useRef(false);
  const retryUsedRef = useRef(false);
  const [retryToken, setRetryToken] = useState(0);

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      if (completedRef.current || inFlightRef.current) return;
      inFlightRef.current = true;
      void dispatch("CREATE_FINISH").then(
        () => {
          inFlightRef.current = false;
          completedRef.current = true;
        },
        () => {
          inFlightRef.current = false;
          if (!active || retryUsedRef.current) return;
          retryUsedRef.current = true;
          setRetryToken((current) => current + 1);
        },
      );
    }, reducedMotion
      ? motionConfig.reducedMotion.instantDurationMillis
      : motionConfig.creation.finishDelayMillis);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [dispatch, reducedMotion, retryToken]);

  return (
    <main
      className="pet-creating-stage"
      aria-label="Создаем друга"
      aria-busy="true"
    >
      <div className="pet-creating-stage__content">
        <div className="creation-mosaic">
          <CreationMosaic reducedMotion={reducedMotion} />
        </div>
        <div
          className="pet-creating-stage__label"
          role="status"
          aria-live="polite"
        >
          Создаем друга
        </div>
      </div>
      <p>Мы пришлем уведомление, когда персонаж будет готов</p>
    </main>
  );
}

function QuestionStage({
  state,
  onOpenCustom,
  feedback,
  customTriggerRef,
  questionTitleRef,
  onAnswer,
}: Pick<
  CreateScreenProps,
  "state" | "onOpenCustom" | "feedback"
> & {
  customTriggerRef: RefObject<HTMLButtonElement | null>;
  questionTitleRef: RefObject<HTMLHeadingElement | null>;
  onAnswer(answer: string): void;
}) {
  return (
    <section className="creation-question" aria-label={`Шаг ${state.step + 1} из 5`}>
      <div className="creation-bottom-gradient creation-bottom-gradient--question" />
      <h1
        ref={questionTitleRef}
        className="creation-question__title"
        tabIndex={-1}
      >
        {state.title}
      </h1>
      <div className="creation-options">
        {[...state.options, "Свой вариант"].map((option, index) => (
          <TiltedCreationButton
            key={`${state.step}-${option}`}
            label={option}
            index={index}
            delayMillis={index * motionConfig.creation.optionEntranceStaggerMillis}
            buttonRef={option === "Свой вариант" ? customTriggerRef : undefined}
            onClick={() => {
              if (option === "Свой вариант") {
                feedback("createCustom");
                onOpenCustom();
              } else {
                feedback("createAnswer");
                onAnswer(option);
              }
            }}
          />
        ))}
      </div>
    </section>
  );
}

function CustomStage({ state, customValue, onUpdateCustom, feedback, onSubmit }: Pick<
  CreateScreenProps,
  "state" | "customValue" | "onUpdateCustom" | "feedback"
> & { onSubmit(answer: string): void }) {
  const submit = () => {
    const answer = customValue.trim();
    if (answer) {
      feedback("createCustom");
      onSubmit(answer);
    }
  };
  const canSubmit = customValue.trim().length > 0;
  return (
    <section className="creation-custom" data-testid="create-custom">
      <h1 className="creation-custom__question">{state.title}</h1>
      <label className="creation-custom__field">
        <span className="sr-only">
          {state.step === 0 ? "Свой вариант персонажа" : `Свой вариант: ${state.title}`}
        </span>
        <textarea
          autoFocus
          autoCapitalize="sentences"
          enterKeyHint="done"
          maxLength={300}
          rows={4}
          placeholder="Свой вариант"
          value={customValue}
          onChange={(event) => onUpdateCustom(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey && canSubmit) {
              event.preventDefault();
              submit();
            }
          }}
        />
      </label>
      {canSubmit ? (
        <div className="creation-custom__next">
          <TiltedCreationButton
            label="Далее"
            index={1}
            animateEntrance={false}
            onClick={submit}
          />
        </div>
      ) : null}
    </section>
  );
}

function projectAnsweredCreate(
  state: CreateSnapshot,
  reducedMotion: boolean,
): CreateSnapshot {
  if (state.nextQuestion === null && state.step < 4) return state;
  const nextStep = Math.min(state.step + 1, 5);
  const nextQuestion = state.nextQuestion;
  const reachedFinalStep = nextStep >= 5;
  return {
    ...state,
    step: nextStep,
    title: reachedFinalStep ? "" : (nextQuestion?.title ?? state.title),
    options: reachedFinalStep ? [] : (nextQuestion ? [...nextQuestion.options] : state.options),
    nextQuestion: null,
    phase: state.step === 0
      ? (reducedMotion ? "formed" : "transition")
      : "formed",
    generation: state.step === 0 ? "running" : state.generation,
    error: null,
    retryTarget: null,
  };
}

function GenerationStage({ state, reducedMotion, feedback, dispatch }: Pick<
  CreateScreenProps,
  "state" | "reducedMotion" | "feedback" | "dispatch"
>) {
  const failed = state.generation === "retryable" || state.generation === "failed";
  const errorMessage = state.error ?? "Не получилось создать питомца. Попробуйте ещё раз.";
  return (
    <section className="creation-generation" aria-busy={!failed}>
      <div className="creation-bottom-gradient creation-bottom-gradient--generation" />
      <div className="creation-generation__content">
        {!failed ? <ThinkingFrames reducedMotion={reducedMotion} /> : null}
        <h1>Персонаж формируется...</h1>
        {failed ? (
          <>
            <p className="creation-generation__error" role="alert">
              {errorMessage}
            </p>
            <div className="creation-generation__retry">
              <TiltedCreationButton
                label="Попробовать снова"
                index={0}
                animateEntrance={false}
                onClick={() => {
                  feedback("createRetry");
                  void dispatch("CREATE_RETRY").catch(() => undefined);
                }}
              />
            </div>
          </>
        ) : (
          <>
            <span className="sr-only" role="status" aria-live="polite">
              Персонаж формируется
            </span>
            <p className="creation-generation__body">
              Это может занять несколько минут. Можешь пока пойти по своим делам, мы тебя позовем
            </p>
          </>
        )}
      </div>
    </section>
  );
}

export function CreateScreen({
  state,
  reducedMotion,
  customOpen,
  customValue,
  onOpenCustom,
  onCloseCustom,
  onUpdateCustom,
  feedback,
  dispatch,
}: CreateScreenProps) {
  const [optimisticState, setOptimisticState] = useState<CreateSnapshot | null>(null);
  const [displayedPhase, setDisplayedPhase] = useState(state.phase);
  const answerTokenRef = useRef(0);
  const completedTransitionRef = useRef(new Set<string>());
  const inFlightTransitionRef = useRef(new Set<string>());
  const retriedTransitionRef = useRef(new Set<string>());
  const authoritativeCreateRef = useRef(state);
  const mountedRef = useRef(true);
  const customTriggerRef = useRef<HTMLButtonElement>(null);
  const questionTitleRef = useRef<HTMLHeadingElement>(null);
  const previousCustomOpenRef = useRef(customOpen);
  const restoreCustomFocusRef = useRef(customOpen);
  const previousQuestionStepRef = useRef(state.step);
  const displayedState = optimisticState ?? state;
  authoritativeCreateRef.current = state;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    setDisplayedPhase(displayedState.phase);
    if (displayedState.phase === "initial") {
      completedTransitionRef.current.clear();
      inFlightTransitionRef.current.clear();
      retriedTransitionRef.current.clear();
    }
  }, [displayedState.phase]);

  useLayoutEffect(() => {
    const wasOpen = previousCustomOpenRef.current;
    previousCustomOpenRef.current = customOpen;
    if (!wasOpen && customOpen) {
      restoreCustomFocusRef.current = true;
      return;
    }
    if (wasOpen && !customOpen && restoreCustomFocusRef.current) {
      customTriggerRef.current?.focus({ preventScroll: true });
    }
  }, [customOpen]);

  useLayoutEffect(() => {
    if (customOpen) return;
    const previousStep = previousQuestionStepRef.current;
    previousQuestionStepRef.current = displayedState.step;
    if (previousStep !== displayedState.step && displayedState.step < 5) {
      questionTitleRef.current?.focus({ preventScroll: true });
    }
  }, [customOpen, displayedState.step]);

  const submitAnswer = useCallback((answer: string, closeCustom: boolean) => {
    const source = optimisticState ?? state;
    const projected = projectAnsweredCreate(source, reducedMotion);
    const token = answerTokenRef.current + 1;
    answerTokenRef.current = token;
    setOptimisticState(projected);
    setDisplayedPhase(projected.phase);
    if (closeCustom) {
      restoreCustomFocusRef.current = false;
      onCloseCustom();
    }
    void dispatch("CREATE_ANSWER", { answer, step: source.step }).then(
      () => {
        if (answerTokenRef.current === token) setOptimisticState(null);
      },
      () => {
        if (answerTokenRef.current === token) setOptimisticState(null);
      },
    );
  }, [dispatch, onCloseCustom, optimisticState, reducedMotion, state]);

  const completeBackgroundTransition = useCallback(() => {
    const transitionKey = `step-${displayedState.step}`;
    setDisplayedPhase("formed");
    if (
      completedTransitionRef.current.has(transitionKey)
      || inFlightTransitionRef.current.has(transitionKey)
    ) return;
    inFlightTransitionRef.current.add(transitionKey);
    void dispatch("CREATE_BACKGROUND_COMPLETE").then(
      () => {
        inFlightTransitionRef.current.delete(transitionKey);
        completedTransitionRef.current.add(transitionKey);
      },
      () => {
        inFlightTransitionRef.current.delete(transitionKey);
        const authoritative = authoritativeCreateRef.current;
        if (
          !mountedRef.current ||
          retriedTransitionRef.current.has(transitionKey)
          || authoritative.step !== displayedState.step
          || authoritative.phase !== "transition"
        ) return;
        retriedTransitionRef.current.add(transitionKey);
        setDisplayedPhase("transition");
      },
    );
  }, [dispatch, displayedState.step]);

  if (displayedState.step >= 5 && displayedState.generation === "ready") {
    return <PetCreatingStage reducedMotion={reducedMotion} dispatch={dispatch} />;
  }

  return (
    <main className="screen screen--create" data-phase={displayedPhase}>
      <ReferencePlane>
        <SegmentedCreationMedia
          phase={displayedPhase}
          reducedMotion={reducedMotion}
          dimmed={customOpen}
          onTransitionComplete={completeBackgroundTransition}
        />
        {customOpen ? (
          <CustomStage
            state={displayedState}
            customValue={customValue}
            onUpdateCustom={onUpdateCustom}
            feedback={feedback}
            onSubmit={(answer) => submitAnswer(answer, true)}
          />
        ) : displayedState.step < 5 ? (
          <QuestionStage
            state={displayedState}
            onOpenCustom={onOpenCustom}
            feedback={feedback}
            customTriggerRef={customTriggerRef}
            questionTitleRef={questionTitleRef}
            onAnswer={(answer) => submitAnswer(answer, false)}
          />
        ) : (
          <GenerationStage
            state={displayedState}
            reducedMotion={reducedMotion}
            feedback={feedback}
            dispatch={dispatch}
          />
        )}
      </ReferencePlane>
      {customOpen ? (
        <button
          className="create-contextual-back"
          type="button"
          aria-label="Назад"
          onClick={() => {
            feedback("buttonPress");
            onCloseCustom();
          }}
        >
          <BackIcon />
        </button>
      ) : null}
    </main>
  );
}
