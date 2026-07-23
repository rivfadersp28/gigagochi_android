import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from "react";
import type {
  DashboardFeedSnapshot,
  DashboardDraftMode,
  DashboardFood,
  DashboardMode,
  DashboardReplySnapshot,
  DashboardSnapshot,
  FirstSessionSnapshot,
  JsonValue,
  PendingOperationStatus,
  ProductCommandType,
  WebFeedbackKind,
} from "./contracts";
import { AnimatedDialogueText } from "./FirstSessionDialogue";
import { motionConfig } from "./motionConfig";

type Dispatch = (type: ProductCommandType, payload?: JsonValue) => Promise<void>;

const REFERENCE_WIDTH = 402;
const REFERENCE_HEIGHT = 874;
const COMPOSER_MIN_HEIGHT = 62;
const COMPOSER_MAX_HEIGHT = 134;
const COMPOSER_LINE_HEIGHT = 24;
const FEED_FAILURE_MESSAGE = "Питомец поел, но не смог ответить. Попробуйте ещё раз.";

function appendContinuation(reply: DashboardReplySnapshot): string {
  const portion = reply.portions[reply.portionIndex] ?? "";
  return reply.hasNextPortion && !portion.endsWith("…") ? `${portion}…` : portion;
}

export interface DashboardReplyPresentation {
  message: string | null;
  revealComplete: boolean;
  canAdvance: boolean;
  firstSessionReplyPending: boolean;
  markRevealComplete(): void;
  advance(): void;
}

export function useDashboardReplyPresentation(
  reply: DashboardReplySnapshot | null,
  firstSession: FirstSessionSnapshot | null,
  reducedMotion: boolean,
  dispatch: Dispatch,
  presentationActive = true,
): DashboardReplyPresentation {
  const identity = reply
    ? `${reply.source}:${reply.requestKey}:${reply.portionIndex}`
    : "none";
  const [presentation, setPresentation] = useState({
    identity,
    revealComplete: reducedMotion,
  });
  const completedEffectKeys = useRef(new Set<string>());
  const inFlightEffectKeys = useRef(new Set<string>());
  const retriedEffectKeys = useRef(new Set<string>());
  const mountedRef = useRef(true);
  const [retrySignals, setRetrySignals] = useState<Record<string, number>>({});

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const requestSingleRetry = useCallback((effectKey: string) => {
    if (!mountedRef.current || retriedEffectKeys.current.has(effectKey)) return;
    retriedEffectKeys.current.add(effectKey);
    setRetrySignals((current) => ({
      ...current,
      [effectKey]: (current[effectKey] ?? 0) + 1,
    }));
  }, []);

  const dispatchConfirmed = useCallback((
    effectKey: string,
    type: ProductCommandType,
    payload: JsonValue,
  ) => {
    if (
      completedEffectKeys.current.has(effectKey)
      || inFlightEffectKeys.current.has(effectKey)
    ) return;
    inFlightEffectKeys.current.add(effectKey);
    void dispatch(type, payload).then(
      () => {
        inFlightEffectKeys.current.delete(effectKey);
        completedEffectKeys.current.add(effectKey);
      },
      () => {
        inFlightEffectKeys.current.delete(effectKey);
        requestSingleRetry(effectKey);
      },
    );
  }, [dispatch, requestSingleRetry]);

  const revealComplete = presentation.identity === identity
    ? presentation.revealComplete
    : reducedMotion;

  const markRevealComplete = useCallback(() => {
    setPresentation({ identity, revealComplete: true });
  }, [identity]);

  useEffect(() => {
    if (!reducedMotion) return;
    setPresentation({ identity, revealComplete: true });
  }, [identity, reducedMotion]);

  const presentedEffectKey = reply?.source === "chat"
    ? `presented:${reply.requestKey}`
    : "presented:none";
  const presentedRetrySignal = retrySignals[presentedEffectKey] ?? 0;
  useEffect(() => {
    if (!presentationActive || reply?.source !== "chat") return;
    dispatchConfirmed(
      presentedEffectKey,
      "CHAT_REPLY_PRESENTED",
      { requestKey: reply.requestKey },
    );
  }, [
    dispatchConfirmed,
    presentationActive,
    presentedEffectKey,
    presentedRetrySignal,
    reply?.requestKey,
    reply?.source,
  ]);

  const advanceEffectKey = `advance:${identity}`;
  const advanceRetrySignal = retrySignals[advanceEffectKey] ?? 0;
  useEffect(() => {
    if (!presentationActive || !reply?.hasNextPortion) return;
    const timer = window.setTimeout(() => {
      dispatchConfirmed(
        advanceEffectKey,
        "REPLY_ADVANCE",
        { requestKey: reply.requestKey },
      );
    }, reply.autoAdvanceDelayMillis);
    return () => window.clearTimeout(timer);
  }, [
    advanceEffectKey,
    advanceRetrySignal,
    dispatchConfirmed,
    presentationActive,
    reply?.autoAdvanceDelayMillis,
    reply?.hasNextPortion,
    reply?.requestKey,
  ]);

  const completeEffectKey = reply ? `complete:${reply.requestKey}` : "complete:none";
  const completeRetrySignal = retrySignals[completeEffectKey] ?? 0;
  const completeRequestKey = reply?.requestKey;
  const completeSource = reply?.source;
  const completeHasNextPortion = reply?.hasNextPortion ?? false;
  const hasFirstSession = firstSession !== null;
  useEffect(() => {
    if (
      !completeRequestKey ||
      !presentationActive ||
      !hasFirstSession ||
      completeHasNextPortion ||
      !revealComplete ||
      !completeSource ||
      !["chat", "feed", "firstSession"].includes(completeSource) ||
      completedEffectKeys.current.has(completeEffectKey)
    ) return;
    dispatchConfirmed(
      completeEffectKey,
      "REPLY_COMPLETE",
      { requestKey: completeRequestKey },
    );
  }, [
    completeHasNextPortion,
    completeEffectKey,
    completeRequestKey,
    completeRetrySignal,
    completeSource,
    dispatchConfirmed,
    hasFirstSession,
    presentationActive,
    revealComplete,
  ]);

  const advance = useCallback(() => {
    if (!presentationActive || !reply?.hasNextPortion) return;
    dispatchConfirmed(
      advanceEffectKey,
      "REPLY_ADVANCE",
      { requestKey: reply.requestKey },
    );
  }, [advanceEffectKey, dispatchConfirmed, presentationActive, reply]);

  return {
    message: reply ? appendContinuation(reply) : null,
    revealComplete,
    canAdvance: Boolean(reply?.hasNextPortion),
    firstSessionReplyPending: reply?.source === "firstSession",
    markRevealComplete,
    advance,
  };
}

interface ThinkingIndicatorProps {
  mode: "chat" | "feed";
  reducedMotion: boolean;
  queued?: boolean;
  active?: boolean;
}

export function ThinkingIndicator({
  mode,
  reducedMotion,
  queued = false,
  active = true,
}: ThinkingIndicatorProps) {
  const [frame, setFrame] = useState(0);
  useEffect(() => {
    if (reducedMotion || !active) {
      setFrame(0);
      return;
    }
    const timer = window.setInterval(
      () => setFrame((current) => (current + 1) % 3),
      motionConfig.shared.thinkingFrameIntervalMillis,
    );
    return () => window.clearInterval(timer);
  }, [active, reducedMotion]);

  return (
    <div
      className={`thinking-indicator thinking-indicator--${mode}${queued ? " thinking-indicator--queued" : ""}`}
      role="status"
      aria-label={queued ? "Персонаж думает. Следующее сообщение в очереди" : "Персонаж думает"}
      data-frame={frame + 1}
    >
      <img src={`/res/thinking_frame_${frame + 1}.svg`} alt="" />
    </div>
  );
}

interface ConversationInputProps {
  mode: DashboardDraftMode;
  initialValue: string;
  error: string | null;
  busy: boolean;
  thinking?: boolean;
  retryable?: boolean;
  experienceCost?: number;
  pendingStatus?: PendingOperationStatus | null;
  pendingLabel?: string | null;
  feedback(kind: WebFeedbackKind): void;
  dispatch: Dispatch;
  onDraftChange?(mode: DashboardDraftMode, value: string): void;
  flushDraft?(mode: DashboardDraftMode, value?: string): Promise<void>;
  onLineCountChange?(lineCount: number): void;
}

const BLOCKING_OPERATION_STATUSES = new Set<PendingOperationStatus>([
  "pending",
  "dispatching",
  "attached",
  "ready",
  "retryable",
  "outcomeUnknown",
]);

function operationStatusMessage(
  mode: "outfit" | "travel",
  status: PendingOperationStatus,
  label: string | null,
  canRetry: boolean,
  error: string | null,
): string {
  const subject = label?.trim();
  if (error) return subject ? `${error} · ${subject}` : error;
  if (canRetry) {
    const failure = mode === "outfit"
      ? "Не получилось нарядить персонажа. Попробуйте ещё раз."
      : "Не получилось отправиться в путешествие. Попробуйте ещё раз.";
    return subject ? `${failure} · ${subject}` : failure;
  }
  let prefix: string;
  if (status === "outcomeUnknown") prefix = "Проверяю статус запроса";
  else if (status === "completed") prefix = mode === "outfit" ? "Наряд готов" : "Путешествие готово";
  else if (status === "failed" || status === "applyConflict") {
    prefix = "Предыдущий запрос завершился с ошибкой";
  } else prefix = mode === "outfit" ? "Наряд создаётся" : "Путешествие готовится";
  return subject ? `${prefix}: ${subject}` : prefix;
}

export function ConversationInput({
  mode,
  initialValue,
  error,
  busy,
  thinking = false,
  retryable = false,
  experienceCost = 200,
  pendingStatus = null,
  pendingLabel = null,
  feedback,
  dispatch,
  onDraftChange,
  flushDraft,
  onLineCountChange,
}: ConversationInputProps) {
  const [value, setValue] = useState(initialValue.slice(0, 1_000));
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const valueRef = useRef(value);
  const activeModeRef = useRef(mode);
  const locallyEditedRef = useRef(false);
  const retryInFlightRef = useRef(false);

  const measure = useCallback((input: HTMLTextAreaElement, reset = false) => {
    if (reset) input.style.height = `${COMPOSER_MIN_HEIGHT}px`;
    else {
      input.style.height = "auto";
      input.style.height = `${Math.min(COMPOSER_MAX_HEIGHT, Math.max(COMPOSER_MIN_HEIGHT, input.scrollHeight))}px`;
    }
    const overflowHeight = Math.max(0, input.scrollHeight - COMPOSER_MIN_HEIGHT);
    onLineCountChange?.(reset
      ? 1
      : Math.max(1, Math.min(4, 1 + Math.ceil(overflowHeight / COMPOSER_LINE_HEIGHT))));
  }, [onLineCountChange]);

  useLayoutEffect(() => {
    const projected = initialValue.slice(0, 1_000);
    if (activeModeRef.current !== mode) {
      activeModeRef.current = mode;
      valueRef.current = projected;
      locallyEditedRef.current = false;
      setValue(projected);
      return;
    }
    if (projected === valueRef.current) {
      locallyEditedRef.current = false;
    } else if (!locallyEditedRef.current) {
      valueRef.current = projected;
      setValue(projected);
    }
  }, [initialValue, mode]);

  useEffect(() => {
    inputRef.current?.focus();
  }, [mode]);

  useLayoutEffect(() => {
    if (inputRef.current) measure(inputRef.current, !value);
  }, [measure, mode, value]);

  const chatRetryAction = mode === "chat" && retryable && !thinking;
  const operationRetryAction = mode !== "chat" && (
    (pendingStatus === "retryable" && !thinking) ||
    (pendingStatus === "pending" && error !== null && !thinking)
  );
  const retryAction = chatRetryAction || operationRetryAction;
  const content = mode === "chat"
    ? ["Расскажи о себе", chatRetryAction ? "Повторить отправку" : "Отправить сообщение"]
    : mode === "outfit"
      ? [
        "В футболку Metallica",
        retryAction ? "Повторить создание наряда" : `Создать наряд за ${experienceCost} монет`,
      ]
      : [
        "На ночной рынок духов",
        retryAction ? "Повторить путешествие" : "Отправить в путешествие",
      ];
  const isChat = mode === "chat";
  const operationBlocksSubmit = pendingStatus !== null && BLOCKING_OPERATION_STATUSES.has(pendingStatus);
  const inputBusy = submitting || (isChat ? chatRetryAction : busy || operationBlocksSubmit);
  const buttonDisabled = isChat
    ? submitting || (chatRetryAction ? busy : !value.trim())
    : submitting || busy || (!retryAction && (operationBlocksSubmit || !value.trim()));
  const operationMessage = !isChat && pendingStatus && !thinking
    ? operationStatusMessage(mode, pendingStatus, pendingLabel, operationRetryAction, error)
    : null;

  const submit = () => {
    const rawValue = value.slice(0, 1_000);
    const text = rawValue.trim().slice(0, 1_000);
    if (buttonDisabled || retryInFlightRef.current) return;
    if (retryAction) {
      retryInFlightRef.current = true;
      setSubmitting(true);
      inputRef.current?.blur();
      feedback("buttonPress");
      const command = mode === "chat"
        ? "CHAT_RETRY"
        : mode === "outfit"
          ? "OUTFIT_RETRY"
          : "TRAVEL_RETRY";
      void dispatch(command, undefined)
        .catch(() => undefined)
        .finally(() => {
          retryInFlightRef.current = false;
          setSubmitting(false);
        });
      return;
    }
    if (isChat) {
      valueRef.current = "";
      locallyEditedRef.current = true;
      setValue("");
      if (inputRef.current) measure(inputRef.current, true);
    }
    inputRef.current?.blur();
    feedback(mode === "chat" ? "chatSubmit" : "buttonPress");
    const send = async () => {
      if (flushDraft) {
        setSubmitting(true);
        await flushDraft(mode, rawValue);
      }
      if (mode === "chat") await dispatch("CHAT_SEND", { message: text });
      if (mode === "outfit") await dispatch("OUTFIT_SUBMIT", { prompt: text });
      if (mode === "travel") await dispatch("TRAVEL_SUBMIT", { prompt: text });
    };
    void send().catch(() => {
      if (mode !== "chat") return;
      valueRef.current = text;
      locallyEditedRef.current = true;
      setValue((current) => current || text);
      onDraftChange?.(mode, text);
    }).finally(() => {
      if (flushDraft) setSubmitting(false);
    });
  };

  return (
    <section className={`dashboard-input dashboard-input--${mode}${operationMessage ? " dashboard-input--has-operation" : ""}${error ? " dashboard-input--has-error" : ""}`}>
      <label>
        <span className="sr-only">{content[0]}</span>
        <textarea
          ref={inputRef}
          autoFocus
          maxLength={1_000}
          placeholder={content[0]}
          value={value}
          disabled={inputBusy}
          aria-busy={submitting || (!isChat && (thinking || (operationBlocksSubmit && !retryAction)))}
          aria-invalid={Boolean(error)}
          aria-describedby={[
            error && !operationMessage ? `${mode}-input-error` : null,
            operationMessage ? `${mode}-operation-status` : null,
          ].filter(Boolean).join(" ") || undefined}
          onChange={(event) => {
            const next = event.target.value.slice(0, 1_000);
            valueRef.current = next;
            locallyEditedRef.current = true;
            setValue(next);
            onDraftChange?.(mode, next);
            measure(event.currentTarget, !next);
          }}
        />
        {error && !operationMessage
          ? (
              <span
                className="dashboard-input__error"
                id={`${mode}-input-error`}
                role="alert"
              >
                {error}
              </span>
            )
          : null}
        {operationMessage ? (
          <span
            className={`dashboard-input__operation dashboard-input__operation--${pendingStatus}`}
            id={`${mode}-operation-status`}
            role="status"
          >
            {operationMessage}
          </span>
        ) : null}
        <button
          type="button"
          aria-label={content[1]}
          data-operation-action={retryAction ? "retry" : "submit"}
          disabled={buttonDisabled}
          onClick={submit}
        >
          {mode === "outfit" && !retryAction ? (
            <>
              <span>{experienceCost}</span>
              <img src="/res/xp_coin.svg" alt="" />
            </>
          ) : <img src="/res/conversation_send_icon.svg" alt="" />}
        </button>
      </label>
    </section>
  );
}

type FoodMotionPhase = "idle" | "consuming" | "reappearing";

interface FoodMotion {
  pulseId: number;
  food: DashboardFood | null;
  phase: FoodMotionPhase;
}

function useFoodMotion(feed: DashboardFeedSnapshot): {
  motion: FoodMotion;
} {
  const observedPulseId = useRef<number | null>(null);
  const timers = useRef<number[]>([]);
  const [motion, setMotion] = useState<FoodMotion>({
    pulseId: feed.pulseId,
    food: null,
    phase: "idle",
  });

  const start = useCallback((pulseId: number, food: DashboardFood) => {
    timers.current.forEach(window.clearTimeout);
    timers.current = [];
    setMotion({ pulseId, food, phase: "consuming" });
    timers.current.push(window.setTimeout(() => {
      setMotion((current) => current.pulseId === pulseId
        ? { ...current, phase: "reappearing" }
        : current);
    }, motionConfig.dashboard.feedConsumeDurationMillis));
    timers.current.push(window.setTimeout(() => {
      setMotion((current) => current.pulseId === pulseId
        ? { ...current, food: null, phase: "idle" }
        : current);
    }, motionConfig.dashboard.feedConsumeDurationMillis +
      motionConfig.dashboard.feedReappearDurationMillis));
  }, []);

  useEffect(() => {
    if (feed.pulseId === observedPulseId.current) return;
    observedPulseId.current = feed.pulseId;
    if (feed.activeFood) start(feed.pulseId, feed.activeFood);
  }, [feed.activeFood, feed.pulseId, start]);

  useEffect(() => () => timers.current.forEach(window.clearTimeout), []);

  return { motion };
}

interface DragState {
  pointerId: number;
  startClientX: number;
  startClientY: number;
  originCenterX: number;
  originCenterY: number;
  offsetX: number;
  offsetY: number;
  dragged: boolean;
}

interface FoodTokenProps {
  food: DashboardFood;
  label: string;
  rotation: number;
  motion: FoodMotion;
  onActivate(food: DashboardFood, source: "tap" | "drag"): Promise<boolean>;
}

function FoodToken({ food, label, rotation, motion, onActivate }: FoodTokenProps) {
  const [drag, setDrag] = useState<DragState | null>(null);
  const [parkedOffset, setParkedOffset] = useState({ x: 0, y: 0 });
  const dragRef = useRef<DragState | null>(null);
  const animationSeenRef = useRef(false);
  const activationSequenceRef = useRef(0);
  const isMotionTarget = motion.food === food;

  useEffect(() => {
    if (isMotionTarget && motion.phase !== "idle") {
      animationSeenRef.current = true;
      return;
    }
    if (animationSeenRef.current) {
      animationSeenRef.current = false;
      setParkedOffset({ x: 0, y: 0 });
    }
  }, [isMotionTarget, motion.phase, motion.pulseId]);

  const updateDrag = (next: DragState | null) => {
    dragRef.current = next;
    setDrag(next);
  };

  const activate = (source: "tap" | "drag", resetOnRejection: boolean) => {
    activationSequenceRef.current += 1;
    const sequence = activationSequenceRef.current;
    void onActivate(food, source).then((wasAccepted) => {
      if (
        !wasAccepted &&
        resetOnRejection &&
        activationSequenceRef.current === sequence
      ) {
        setParkedOffset({ x: 0, y: 0 });
      }
    });
  };

  const beginDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0) return;
    const plane = event.currentTarget.closest<HTMLElement>(".reference-plane");
    const planeBounds = plane?.getBoundingClientRect();
    const tokenBounds = event.currentTarget.getBoundingClientRect();
    if (!planeBounds || planeBounds.width <= 0 || planeBounds.height <= 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    const scaleX = REFERENCE_WIDTH / planeBounds.width;
    const scaleY = REFERENCE_HEIGHT / planeBounds.height;
    updateDrag({
      pointerId: event.pointerId,
      startClientX: event.clientX,
      startClientY: event.clientY,
      originCenterX: (tokenBounds.left + tokenBounds.width / 2 - planeBounds.left) * scaleX,
      originCenterY: (tokenBounds.top + tokenBounds.height / 2 - planeBounds.top) * scaleY,
      offsetX: 0,
      offsetY: 0,
      dragged: false,
    });
  };

  const moveDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const current = dragRef.current;
    if (!current || current.pointerId !== event.pointerId) return;
    const plane = event.currentTarget.closest<HTMLElement>(".reference-plane");
    const planeBounds = plane?.getBoundingClientRect();
    if (!planeBounds || planeBounds.width <= 0 || planeBounds.height <= 0) return;
    event.preventDefault();
    const offsetX = (event.clientX - current.startClientX) * REFERENCE_WIDTH / planeBounds.width;
    const offsetY = (event.clientY - current.startClientY) * REFERENCE_HEIGHT / planeBounds.height;
    updateDrag({
      ...current,
      offsetX,
      offsetY,
      dragged: current.dragged ||
        Math.hypot(offsetX, offsetY) > motionConfig.dashboard.feedDragActivationDistance,
    });
  };

  const finishDrag = (event: ReactPointerEvent<HTMLButtonElement>, cancelled = false) => {
    const current = dragRef.current;
    if (!current || current.pointerId !== event.pointerId) return;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    updateDrag(null);
    if (cancelled) {
      setParkedOffset({ x: 0, y: 0 });
      return;
    }
    if (!current.dragged) {
      setParkedOffset({ x: 0, y: 0 });
      activate("tap", false);
      return;
    }
    const centerX = current.originCenterX + current.offsetX;
    const centerY = current.originCenterY + current.offsetY;
    const accepted = centerX >= -motionConfig.dashboard.feedDropTolerance &&
      centerX <= REFERENCE_WIDTH + motionConfig.dashboard.feedDropTolerance &&
      centerY >= -motionConfig.dashboard.feedDropTolerance &&
      centerY <= REFERENCE_HEIGHT + motionConfig.dashboard.feedDropTolerance;
    if (accepted) {
      setParkedOffset({ x: current.offsetX, y: current.offsetY });
      activate("drag", true);
    } else {
      setParkedOffset({ x: 0, y: 0 });
    }
  };

  const style = {
    "--food-rotation": `${rotation}deg`,
    "--food-offset-x": `${drag?.offsetX ?? parkedOffset.x}px`,
    "--food-offset-y": `${drag?.offsetY ?? parkedOffset.y}px`,
  } as CSSProperties;
  const phase = isMotionTarget ? motion.phase : "idle";

  return (
    <button
      type="button"
      className={`food-token food-token--${food === "berry-bowl" ? "berry" : "leaf"}${drag?.dragged ? " food-token--dragging" : ""}${phase === "consuming" ? " food-token--consuming" : ""}`}
      style={style}
      aria-label={label}
      data-food={food}
      data-phase={phase}
      onPointerDown={beginDrag}
      onPointerMove={moveDrag}
      onPointerUp={(event) => finishDrag(event)}
      onPointerCancel={(event) => finishDrag(event, true)}
      onClick={(event) => {
        if (event.detail === 0) activate("tap", false);
      }}
    >
      <span className={`food-token__motion food-token__motion--${phase}`}>
        <img src={`/res/feed_food_${food === "berry-bowl" ? "berry_bowl" : "leaf_crunch"}.svg`} alt="" />
      </span>
    </button>
  );
}

interface FeedModeLayerProps {
  dashboard: DashboardSnapshot;
  reducedMotion: boolean;
  feedback(kind: WebFeedbackKind): void;
  dispatch: Dispatch;
}

export function FeedModeLayer({ dashboard, reducedMotion, feedback, dispatch }: FeedModeLayerProps) {
  const { motion } = useFoodMotion(dashboard.feed);
  const [dispatchError, setDispatchError] = useState<string | null>(null);
  const activationSequence = useRef(0);
  const activate = useCallback(async (food: DashboardFood, source: "tap" | "drag") => {
    activationSequence.current += 1;
    const sequence = activationSequence.current;
    setDispatchError(null);
    if (source === "tap") feedback("buttonPress");
    try {
      await dispatch("FEED_CONSUME", { food });
      if (activationSequence.current === sequence) setDispatchError(null);
      return true;
    } catch {
      if (activationSequence.current === sequence) setDispatchError(FEED_FAILURE_MESSAGE);
      return false;
    }
  }, [dispatch, feedback]);
  const pulseVisible = motion.food !== null;
  const liveMessage = motion.food
    ? motion.food === "berry-bowl"
      ? "Питомец получил ягоды"
      : "Питомец получил лист"
    : "";

  return (
    <>
      {pulseVisible ? (
        <span
          key={motion.pulseId}
          className={reducedMotion ? "feed-pulse feed-pulse--reduced" : "feed-pulse"}
          aria-hidden="true"
        />
      ) : null}
      <span className="sr-only" aria-live="polite">{liveMessage}</span>
      {dashboard.feed.error ?? dispatchError ? (
        <div className="feed-error" role="status">{dashboard.feed.error ?? dispatchError}</div>
      ) : null}
      <section className="feed-shelf" aria-label="Еда">
        <FoodToken
          food="berry-bowl"
          label="Ягодная миска"
          rotation={-8}
          motion={motion}
          onActivate={activate}
        />
        <FoodToken
          food="leaf-crunch"
          label="Хрустящий лист"
          rotation={6}
          motion={motion}
          onActivate={activate}
        />
      </section>
    </>
  );
}

export function useComposerLift(lineCount: number): CSSProperties {
  return useMemo(() => ({
    "--composer-dialogue-lift": `${Math.max(0, lineCount - 2) * 24}px`,
  } as CSSProperties), [lineCount]);
}
