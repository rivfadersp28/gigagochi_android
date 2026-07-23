import type {
  AppLifecycleState,
  DashboardDraftMode,
  DashboardSnapshot,
  DashboardMode,
  FirstSessionSnapshot,
  JsonValue,
  PetTapFeedbackSnapshot,
  PetSnapshot,
  ProductCommandType,
  WebFeedbackKind,
} from "./contracts";
import { ReferencePlane, type ReferencePlaneMetrics } from "./ReferencePlane";
import { PetTapHeartCanvas, type PetTapHeartCanvasHandle } from "./PetTapHeartCanvas";
import { usePetTapBulge } from "./usePetTapBulge";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";
import {
  AnimatedDialogueText,
  useFirstSessionPresentation,
  type AnimatedDialogueTextHandle,
} from "./FirstSessionDialogue";
import {
  ConversationInput,
  FeedModeLayer,
  ThinkingIndicator,
  useComposerLift,
  useDashboardReplyPresentation,
} from "./DashboardInteractionLayer";
import { DashboardSceneMedia } from "./DashboardSceneMedia";
import { ExperiencePill, StatusRing } from "./DashboardHud";
import { motionConfig } from "./motionConfig";
import { usePressMotion } from "./usePressMotion";

type Dispatch = (type: ProductCommandType, payload?: JsonValue) => Promise<void>;

interface DashboardScreenProps {
  pet: PetSnapshot;
  mode: DashboardMode;
  busy: boolean;
  petTapFeedback: PetTapFeedbackSnapshot | null;
  reducedMotion: boolean;
  lifecycleState?: AppLifecycleState;
  active?: boolean;
  eventsBadgeCount?: number;
  firstSession: FirstSessionSnapshot | null;
  dashboard: DashboardSnapshot | null;
  onOpenMode(mode: DashboardMode): void;
  onCloseMode(): void;
  feedback(kind: WebFeedbackKind): void;
  dispatch: Dispatch;
  onDraftChange?(mode: DashboardDraftMode, value: string): void;
  flushDraft?(mode: DashboardDraftMode, value?: string): Promise<void>;
  onPetTapInteractionFeedback?(): void;
}

const NOOP = () => undefined;
const PREFERRED_ACTION_TOP = 762;
const ACTION_HEIGHT = 58.203;
const ACTION_BOTTOM_MARGIN = 16;
const FEED_ROW_HEIGHT = 148;
const FEED_CARDS_VERTICAL_OFFSET = 20;
const DIALOGUE_INPUT_GAP = 24;
const DIALOGUE_CONTAINER_HEIGHT = 132;
const THINKING_INDICATOR_HEIGHT = 55.5;

interface PetPointerGesture {
  pointerId: number;
  referenceX: number;
  referenceY: number;
  downClientX: number;
  downClientY: number;
  cancelled: boolean;
}

export interface DashboardViewportGeometry {
  visibleReferenceBottom: number;
  actionTop: number;
  feedTop: number;
}

export interface DashboardConversationGeometry {
  inputTopInReference: number;
  dialogueTop: number;
  thinkingTop: number;
}

export function dashboardViewportGeometry(
  viewportHeight: number,
  safeBottom: number,
  scale: number,
): DashboardViewportGeometry {
  const resolvedScale = Number.isFinite(scale) && scale > 0 ? scale : 1;
  const visibleReferenceBottom = Math.max(0, viewportHeight - safeBottom) / resolvedScale;
  const preferredBottom = PREFERRED_ACTION_TOP + ACTION_HEIGHT + ACTION_BOTTOM_MARGIN;
  const overlap = Math.max(0, preferredBottom - visibleReferenceBottom);
  const actionTop = PREFERRED_ACTION_TOP - overlap;
  return {
    visibleReferenceBottom,
    actionTop,
    feedTop: actionTop + ACTION_HEIGHT - FEED_ROW_HEIGHT + FEED_CARDS_VERTICAL_OFFSET,
  };
}

export function dashboardConversationGeometry(
  inputTopRoot: number,
  referenceTopRoot: number,
  scale: number,
  composerLineCount: number,
): DashboardConversationGeometry {
  const resolvedScale = Number.isFinite(scale) && scale > 0 ? scale : 1;
  const inputTopInReference = (inputTopRoot - referenceTopRoot) / resolvedScale;
  const composerLift = Math.max(0, composerLineCount - 2) * 24;
  return {
    inputTopInReference,
    dialogueTop: inputTopInReference - DIALOGUE_INPUT_GAP - DIALOGUE_CONTAINER_HEIGHT - composerLift,
    thinkingTop: inputTopInReference - DIALOGUE_INPUT_GAP - THINKING_INDICATOR_HEIGHT,
  };
}

const dashboardActions: Array<{
  mode?: DashboardMode;
  label: string;
  icon: string | null;
}> = [
  { mode: "chat", label: "Поболтать", icon: "/res/action_chat_icon_new.svg" },
  { mode: "feed", label: "Покормить", icon: "/res/action_feed_icon_new.svg" },
  { label: "События", icon: null },
  { mode: "outfit", label: "Нарядить", icon: "/res/action_outfit_icon.svg" },
  { mode: "travel", label: "В путешествие", icon: "/res/action_travel_icon_new.svg" },
];

function EventsGlyph() {
  return (
    <svg viewBox="0 0 28 28" aria-hidden="true">
      <g transform="translate(2.5 .2) scale(.82)">
        <path d="M14 2.3a2.03 2.03 0 0 1 2.03 2.03v.12a8.88 8.88 0 0 1 6.59 8.62v4.75c0 .97.56 1.82 1.3 2.41 1.5 1.19 2.39 2.83 2.39 4.23 0 3.5-5.51 6.34-12.31 6.34S1.69 27.96 1.69 24.46c0-1.4.89-3.04 2.39-4.23.74-.59 1.3-1.44 1.3-2.41v-4.75a8.88 8.88 0 0 1 6.59-8.62v-.12A2.03 2.03 0 0 1 14 2.3Zm0 18.37c-5.3 0-9.6 1.7-9.6 3.79 0 2.1 4.3 3.8 9.6 3.8s9.6-1.7 9.6-3.8c0-2.09-4.3-3.79-9.6-3.79Z" />
      </g>
      <path d="M10.6 23.2a3.4 3.4 0 0 0 6.8 0c-1.03-.16-2.17-.26-3.4-.26s-2.37.1-3.4.26Z" />
    </svg>
  );
}

function TravelGlyph() {
  return (
    <svg viewBox="0 0 28 28.203" aria-hidden="true">
      <path
        fill="rgb(255 255 255 / 30%)"
        d="M18.0833 1.1668H20.8333A1.75 1.75 0 0 1 22.5833 2.9168V14H16.3333V2.9168A1.75 1.75 0 0 1 18.0833 1.1668M7.5833 1.1668H10.3333A1.75 1.75 0 0 1 12.0833 2.9168V14H5.8333V2.9168A1.75 1.75 0 0 1 7.5833 1.1668"
      />
      <path
        fill="rgb(255 255 255 / 30%)"
        stroke="rgb(0 0 0 / 30%)"
        strokeWidth="1.86667"
        d="M13.9997 4.8999C19.6697 4.8999 24.2661 9.49652 24.2663 15.1665V19.8335C24.2663 20.9079 24.2675 21.7607 24.2204 22.4507C24.1727 23.1489 24.073 23.7486 23.8405 24.3101C23.2721 25.6821 22.1814 26.772 20.8092 27.3403C20.2479 27.5728 19.6481 27.6726 18.9499 27.7202C18.2601 27.7673 17.4077 27.7671 16.3337 27.7671H11.6667C10.5922 27.7671 9.73941 27.7673 9.04948 27.7202C8.35127 27.6726 7.75151 27.5729 7.1901 27.3403C5.81815 26.772 4.72819 25.682 4.15983 24.3101C3.92729 23.7486 3.8276 23.1489 3.77995 22.4507C3.73287 21.7607 3.73307 20.9079 3.73307 19.8335V15.1665C3.73325 9.49663 8.32981 4.90007 13.9997 4.8999Z"
      />
      <path
        fill="rgb(0 0 0 / 30%)"
        d="M9.3333 18.0835A.5833.5833 0 0 1 9.9167 17.5002H18.0833A.5833.5833 0 0 1 18.6667 18.0835V19.8335A2.3333 2.3333 0 0 1 16.3333 22.1668H11.6667A2.3333 2.3333 0 0 1 9.3333 19.8335Z"
      />
    </svg>
  );
}

function DashboardActionButton({
  actionKey,
  label,
  accessibilityLabel,
  badgeCount,
  reducedMotion,
  glyph,
  onClick,
}: {
  actionKey: DashboardMode | "events";
  label: string;
  accessibilityLabel?: string;
  badgeCount: number;
  reducedMotion: boolean;
  glyph: ReactNode;
  onClick(): void;
}) {
  const press = usePressMotion<HTMLButtonElement>({
    disabled: false,
    reducedMotion,
    kind: "dashboardSpring",
  });
  return (
    <button
      ref={press.ref}
      type="button"
      className="glass-action"
      aria-label={accessibilityLabel}
      data-dashboard-action={actionKey}
      data-press-phase={press.phase}
      onPointerDown={press.onPointerDown}
      onPointerUp={press.onPointerUp}
      onPointerCancel={press.onPointerCancel}
      onPointerLeave={press.onPointerLeave}
      onKeyDown={press.onKeyDown}
      onKeyUp={press.onKeyUp}
      onBlur={press.onBlur}
      onAnimationEnd={press.onAnimationEnd}
      onClick={onClick}
    >
      {glyph}
      <span>{label}</span>
      {badgeCount > 0 ? (
        <span className="glass-action__badge" aria-hidden="true">
          {badgeCount > 99 ? "99+" : badgeCount}
        </span>
      ) : null}
    </button>
  );
}

export function DashboardScreen({
  pet,
  mode,
  busy,
  petTapFeedback,
  reducedMotion,
  lifecycleState = "foreground",
  active = true,
  eventsBadgeCount = 0,
  firstSession,
  dashboard,
  onOpenMode,
  onCloseMode,
  feedback,
  dispatch,
  onDraftChange,
  flushDraft,
  onPetTapInteractionFeedback,
}: DashboardScreenProps) {
  const promptMode = mode === "outfit" || mode === "travel";
  const presentationActive = active && lifecycleState === "foreground";
  const showDefaultStatus = !promptMode && (
    firstSession === null ||
    firstSession.stage === "awaiting-completion-message" ||
    firstSession.stage === "completed"
  );
  const showExperience = promptMode || showDefaultStatus;
  const videoRef = useRef<HTMLVideoElement>(null);
  const rootRef = useRef<HTMLElement>(null);
  const heartsRef = useRef<PetTapHeartCanvasHandle>(null);
  const dialogueRef = useRef<AnimatedDialogueTextHandle>(null);
  const actionsRef = useRef<HTMLElement>(null);
  const previousModeRef = useRef<DashboardMode>("idle");
  const scenePointer = useRef<{
    id: number;
    x: number;
    y: number;
    moved: boolean;
    dialogueTarget: boolean;
  } | null>(null);
  const petPointer = useRef<PetPointerGesture | null>(null);
  const pressedPetPointers = useRef(new Set<number>());
  const bulge = usePetTapBulge(reducedMotion);
  const [composerLineCount, setComposerLineCount] = useState(1);
  const composerStyle = useComposerLift(composerLineCount);
  const [referenceMetrics, setReferenceMetrics] = useState<ReferencePlaneMetrics>({
    referenceTopRoot: 0,
    scale: 1,
    viewportWidth: 402,
    viewportHeight: 874,
  });
  const [inputSurfaceTopRoot, setInputSurfaceTopRoot] = useState<number | null>(null);
  const [safeBottom, setSafeBottom] = useState(0);

  const onReferenceMetricsChange = useCallback((next: ReferencePlaneMetrics) => {
    setReferenceMetrics((current) => (
      Math.abs(current.referenceTopRoot - next.referenceTopRoot) < .01 &&
      Math.abs(current.scale - next.scale) < .0001 &&
      Math.abs(current.viewportWidth - next.viewportWidth) < .01 &&
      Math.abs(current.viewportHeight - next.viewportHeight) < .01
        ? current
        : next
    ));
  }, []);

  const measureViewportOverlays = useCallback(() => {
    const root = rootRef.current;
    if (!root) return;
    const parsedSafeBottom = Number.parseFloat(
      window.getComputedStyle(root).getPropertyValue("--safe-bottom"),
    );
    const nextSafeBottom = Number.isFinite(parsedSafeBottom) ? Math.max(0, parsedSafeBottom) : 0;
    setSafeBottom((current) => Math.abs(current - nextSafeBottom) < .01 ? current : nextSafeBottom);

    const inputSurface = root.querySelector<HTMLElement>(".dashboard-input label");
    const bounds = inputSurface?.getBoundingClientRect();
    const nextInputTop = bounds && bounds.height > 0 && Number.isFinite(bounds.top)
      ? bounds.top
      : null;
    setInputSurfaceTopRoot((current) => (
      current === nextInputTop ||
      (current !== null && nextInputTop !== null && Math.abs(current - nextInputTop) < .01)
        ? current
        : nextInputTop
    ));
  }, []);

  useLayoutEffect(() => {
    measureViewportOverlays();
  });

  useLayoutEffect(() => {
    const previousMode = previousModeRef.current;
    previousModeRef.current = mode;
    if (mode === "feed" && previousMode !== "feed") {
      rootRef.current
        ?.querySelector<HTMLButtonElement>('.food-token[data-food="berry-bowl"]')
        ?.focus({ preventScroll: true });
      return;
    }
    if (mode === "idle" && previousMode !== "idle") {
      actionsRef.current
        ?.querySelector<HTMLButtonElement>(`[data-dashboard-action="${previousMode}"]`)
        ?.focus({ preventScroll: true });
    }
  }, [mode]);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const inputSurface = root.querySelector<HTMLElement>(".dashboard-input label");
    const observer = typeof ResizeObserver === "undefined"
      ? null
      : new ResizeObserver(measureViewportOverlays);
    observer?.observe(root);
    if (inputSurface) observer?.observe(inputSurface);
    window.addEventListener("resize", measureViewportOverlays);
    window.visualViewport?.addEventListener("resize", measureViewportOverlays);
    window.visualViewport?.addEventListener("scroll", measureViewportOverlays);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", measureViewportOverlays);
      window.visualViewport?.removeEventListener("resize", measureViewportOverlays);
      window.visualViewport?.removeEventListener("scroll", measureViewportOverlays);
    };
  }, [measureViewportOverlays, mode]);

  const viewportGeometry = useMemo(() => dashboardViewportGeometry(
    referenceMetrics.viewportHeight,
    safeBottom,
    referenceMetrics.scale,
  ), [referenceMetrics.scale, referenceMetrics.viewportHeight, safeBottom]);
  const conversationGeometry = inputSurfaceTopRoot === null
    ? null
    : dashboardConversationGeometry(
      inputSurfaceTopRoot,
      referenceMetrics.referenceTopRoot,
      referenceMetrics.scale,
      composerLineCount,
    );
  const dashboardStyle = {
    ...composerStyle,
    "--dashboard-action-top": `${viewportGeometry.actionTop}px`,
    "--dashboard-feed-top": `${viewportGeometry.feedTop}px`,
    "--dashboard-feed-error-top": `${viewportGeometry.feedTop - 42}px`,
    ...(conversationGeometry === null ? {} : {
      "--dashboard-dialogue-top": `${conversationGeometry.dialogueTop}px`,
      "--dashboard-thinking-top": `${conversationGeometry.thinkingTop}px`,
    }),
  } as CSSProperties;
  const dialogue = useFirstSessionPresentation(
    dashboard ? null : firstSession,
    pet.message,
    reducedMotion,
    presentationActive,
  );
  const reply = useDashboardReplyPresentation(
    dashboard?.reply ?? null,
    firstSession,
    reducedMotion,
    dispatch,
    presentationActive,
  );
  const dashboardPresentation = useMemo<DashboardSnapshot>(() => dashboard ?? ({
    reply: null,
    chat: {
      draft: "",
      error: null,
      activeRequestKey: null,
      queuedRequestKey: null,
      thinking: false,
    },
    feed: {
      error: null,
      activeRequestKey: null,
      activeFood: null,
      audioIndex: null,
      pulseId: 0,
      thinking: false,
    },
    outfit: {
      draft: "",
      error: null,
      activeRequestKey: null,
      thinking: false,
      experienceCost: 200,
      pending: null,
    },
    travel: {
      draft: "",
      error: null,
      activeRequestKey: null,
      thinking: false,
      pending: null,
    },
  }), [dashboard]);
  const onboardingSession = firstSession?.stage === "completed" ? null : firstSession;
  const allowedAction = onboardingSession?.allowedAction ?? null;
  const onboardingActionVisible = dashboard
    ? !reply.firstSessionReplyPending
    : dialogue.actionVisible;
  const visibleActions = onboardingSession
    ? onboardingActionVisible && allowedAction
      ? dashboardActions.filter((action) => action.mode === allowedAction)
      : []
    : dashboardActions;

  useEffect(() => {
    if (!onboardingSession && actionsRef.current) actionsRef.current.scrollLeft = 68;
  }, [onboardingSession]);

  const activeAdvance = reply.message && mode !== "outfit" && mode !== "travel"
    ? reply.advance
    : mode === "idle"
      ? dialogue.advance
      : null;
  const activeCanAdvance = petTapFeedback?.thanks
    ? false
    : reply.message && mode !== "outfit" && mode !== "travel"
      ? reply.canAdvance
      : mode === "idle"
        ? dialogue.canAdvance
        : false;

  const beginScenePointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || !activeCanAdvance) return;
    scenePointer.current = {
      id: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      moved: false,
      dialogueTarget: event.target instanceof Element && Boolean(event.target.closest(".pet-message")),
    };
  };
  const moveScenePointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    const pointer = scenePointer.current;
    if (!pointer || pointer.id !== event.pointerId) return;
    if (Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) > 8) {
      pointer.moved = true;
    }
  };
  const finishScenePointer = (event: ReactPointerEvent<HTMLDivElement>, cancelled = false) => {
    const pointer = scenePointer.current;
    if (!pointer || pointer.id !== event.pointerId) return;
    scenePointer.current = null;
    if (cancelled || pointer.moved || pointer.dialogueTarget || !activeCanAdvance) return;
    dialogueRef.current?.stopSpeech();
    activeAdvance?.();
  };

  const referencePoint = (clientX: number, clientY: number) => {
    const plane = rootRef.current?.querySelector<HTMLElement>(".reference-plane");
    const bounds = plane?.getBoundingClientRect();
    if (!bounds || bounds.width <= 0 || bounds.height <= 0) {
      return { x: 201, y: 348 };
    }
    return {
      x: (clientX - bounds.left) * 402 / bounds.width,
      y: (clientY - bounds.top) * 874 / bounds.height,
    };
  };

  const dispatchAcceptedPetTap = (x: number, y: number) => {
    void dispatch("PET_TAP").then(() => {
      if (!reducedMotion) heartsRef.current?.trigger(x, y);
      bulge.trigger(videoRef.current, x / 402, y / 874, 402, 874);
      onPetTapInteractionFeedback?.();
    }, () => undefined);
  };

  const beginPetPointer = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    const current = petPointer.current;
    if (current) {
      pressedPetPointers.current.add(event.pointerId);
      current.cancelled = true;
      return;
    }
    if (pressedPetPointers.current.size > 0 || event.isPrimary === false) return;

    pressedPetPointers.current.add(event.pointerId);
    const point = referencePoint(event.clientX, event.clientY);
    petPointer.current = {
      pointerId: event.pointerId,
      referenceX: point.x,
      referenceY: point.y,
      downClientX: event.clientX,
      downClientY: event.clientY,
      cancelled: false,
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const movePetPointer = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const current = petPointer.current;
    if (!current || current.pointerId !== event.pointerId || current.cancelled) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const movedBeyondTap = Math.hypot(
      event.clientX - current.downClientX,
      event.clientY - current.downClientY,
    ) > motionConfig.petTap.touchSlopCssPixels;
    const movedOutside = event.clientX < bounds.left || event.clientX >= bounds.right ||
      event.clientY < bounds.top || event.clientY >= bounds.bottom;
    if (movedBeyondTap || movedOutside || pressedPetPointers.current.size > 1) {
      current.cancelled = true;
    }
  };

  const finishPetPointer = (
    event: ReactPointerEvent<HTMLButtonElement>,
    cancelled = false,
  ) => {
    pressedPetPointers.current.delete(event.pointerId);
    const current = petPointer.current;
    if (!current || current.pointerId !== event.pointerId) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const releasedOutside = event.clientX < bounds.left || event.clientX >= bounds.right ||
      event.clientY < bounds.top || event.clientY >= bounds.bottom;
    petPointer.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    if (cancelled || current.cancelled || releasedOutside) return;
    dispatchAcceptedPetTap(current.referenceX, current.referenceY);
  };

  useEffect(() => {
    if (mode === "idle") return;
    petPointer.current = null;
    pressedPetPointers.current.clear();
  }, [mode]);

  useEffect(() => {
    const trackPointerDown = (event: PointerEvent) => {
      if (event.pointerType === "mouse" && event.button !== 0) return;
      const current = petPointer.current;
      if (current && current.pointerId !== event.pointerId) current.cancelled = true;
      pressedPetPointers.current.add(event.pointerId);
    };
    const trackPointerEnd = (event: PointerEvent) => {
      pressedPetPointers.current.delete(event.pointerId);
    };
    window.addEventListener("pointerdown", trackPointerDown);
    window.addEventListener("pointerup", trackPointerEnd);
    window.addEventListener("pointercancel", trackPointerEnd);
    return () => {
      window.removeEventListener("pointerdown", trackPointerDown);
      window.removeEventListener("pointerup", trackPointerEnd);
      window.removeEventListener("pointercancel", trackPointerEnd);
      petPointer.current = null;
      pressedPetPointers.current.clear();
    };
  }, []);

  return (
    <main
      ref={rootRef}
      className={`screen screen--dashboard dashboard-mode--${mode}`}
      style={dashboardStyle}
    >
      <ReferencePlane
        onMetricsChange={onReferenceMetricsChange}
        onPointerDownCapture={beginScenePointer}
        onPointerMoveCapture={moveScenePointer}
        onPointerUpCapture={(event) => finishScenePointer(event)}
        onPointerCancelCapture={(event) => finishScenePointer(event, true)}
      >
        <div className="dashboard-scene-fallback" aria-hidden="true" />
        <DashboardSceneMedia
          media={pet.media}
          mode={mode}
          reducedMotion={reducedMotion}
          foreground={lifecycleState === "foreground" && active}
          videoRef={videoRef}
        />
        <canvas
          ref={bulge.canvasRef}
          className={bulge.visible ? "pet-tap-bulge pet-tap-bulge--visible" : "pet-tap-bulge"}
          aria-hidden="true"
        />
        <PetTapHeartCanvas ref={heartsRef} />

        {showExperience ? <ExperiencePill experience={pet.experience} /> : null}
        {showDefaultStatus ? (
          <div className="stats-column">
            <StatusRing kind="hunger" label="Сытость" value={pet.hunger} />
            <StatusRing kind="mood" label="Настроение" value={pet.happiness} />
            <StatusRing kind="energy" label="Энергия" value={pet.energy} />
          </div>
        ) : null}

        {mode === "idle" ? (
          <button
            className="pet-hit-target"
            type="button"
            aria-label={`Погладить ${pet.name}`}
            onPointerDown={beginPetPointer}
            onPointerMove={movePetPointer}
            onPointerUp={(event) => finishPetPointer(event)}
            onPointerCancel={(event) => finishPetPointer(event, true)}
            onPointerLeave={(event) => {
              const current = petPointer.current;
              if (current?.pointerId === event.pointerId) current.cancelled = true;
            }}
            onLostPointerCapture={(event) => {
              const current = petPointer.current;
              if (current?.pointerId === event.pointerId) current.cancelled = true;
            }}
            onClick={(event) => {
              if (event.detail === 0) dispatchAcceptedPetTap(201, 348);
            }}
          />
        ) : null}

        {petTapFeedback?.thanks ? (
          <AnimatedDialogueText
            key={petTapFeedback.eventId}
            ref={dialogueRef}
            message={petTapFeedback.thanks}
            reducedMotion={reducedMotion}
            canAdvance={false}
            active={presentationActive}
            onRevealComplete={NOOP}
            onAdvance={NOOP}
          />
        ) : mode === "chat" && dashboardPresentation.chat.thinking ? (
          <ThinkingIndicator
            mode="chat"
            reducedMotion={reducedMotion}
            queued={dashboardPresentation.chat.queuedRequestKey !== null}
            active={presentationActive}
          />
        ) : mode === "feed" && dashboardPresentation.feed.thinking ? (
          <ThinkingIndicator
            mode="feed"
            reducedMotion={reducedMotion}
            active={presentationActive}
          />
        ) : reply.message && mode !== "outfit" && mode !== "travel" ? (
          <AnimatedDialogueText
            ref={dialogueRef}
            message={reply.message}
            reducedMotion={reducedMotion}
            canAdvance={reply.canAdvance}
            active={presentationActive}
            onRevealComplete={reply.markRevealComplete}
            onAdvance={reply.advance}
            className={`pet-message--${mode}`}
            animateEntrance={dashboard?.reply?.source !== "settled"}
          />
        ) : mode === "idle" ? (
          <AnimatedDialogueText
            ref={dialogueRef}
            message={dialogue.message}
            reducedMotion={reducedMotion}
            canAdvance={dialogue.canAdvance}
            active={presentationActive}
            onRevealComplete={dialogue.markRevealComplete}
            onAdvance={dialogue.advance}
          />
        ) : null}

        {mode === "feed" ? (
          <FeedModeLayer
            dashboard={dashboardPresentation}
            reducedMotion={reducedMotion}
            feedback={feedback}
            dispatch={dispatch}
          />
        ) : promptMode ? (
          <AnimatedDialogueText
            message={mode === "outfit" ? "Во что мне нарядиться?" : "Куда мне отправиться?"}
            reducedMotion={reducedMotion}
            canAdvance={false}
            active={presentationActive}
            onRevealComplete={NOOP}
            onAdvance={NOOP}
            className={`pet-message--${mode} pet-message--prompt`}
          />
        ) : null}

        {mode === "idle" ? (
          <nav
            ref={actionsRef}
            className={onboardingSession ? "dashboard-actions dashboard-actions--onboarding" : "dashboard-actions"}
            aria-label="Действия с питомцем"
          >
            {visibleActions.map((action) => {
              const isEvents = action.mode === undefined;
              const navigateRoute = isEvents
                ? "events"
                : onboardingSession && allowedAction === "travel" && action.mode === "travel"
                  ? "travel"
                  : null;
              const badgeCount = isEvents ? eventsBadgeCount : 0;
              return (
              <DashboardActionButton
                key={action.label}
                actionKey={action.mode ?? "events"}
                label={onboardingSession && allowedAction === "travel"
                  ? "Помочь летучей мыши"
                  : action.label}
                accessibilityLabel={badgeCount > 0
                  ? `${action.label}, требуют внимания: ${badgeCount}`
                  : undefined}
                badgeCount={badgeCount}
                reducedMotion={reducedMotion}
                glyph={onboardingSession && allowedAction === "travel" ? null : (
                  <span
                    className={`glass-action__glyph${action.mode === "outfit" ? " glass-action__glyph--outfit" : ""}`}
                    aria-hidden="true"
                  >
                    {action.mode === "travel"
                      ? <TravelGlyph />
                      : action.icon
                        ? <img src={action.icon} alt="" />
                        : <EventsGlyph />}
                  </span>
                )}
                onClick={() => {
                  feedback("dashboardAction");
                  if (navigateRoute) {
                    void dispatch("NAVIGATE", { route: navigateRoute });
                  } else if (action.mode) {
                    onOpenMode(action.mode);
                  }
                }}
              />
            );})}
          </nav>
        ) : null}
      </ReferencePlane>

      <div className="pet-level"><span>Уровень: {pet.stageLabel}</span></div>

      {mode !== "idle" ? (
        <button
          className="glass-back dashboard-back"
          type="button"
          aria-label="Назад"
          onClick={() => {
            feedback("buttonPress");
            onCloseMode();
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20 11H7.83L13.42 5.41 12 4 4 12 12 20 13.41 18.59 7.83 13H20Z" />
          </svg>
        </button>
      ) : null}

      {mode !== "idle" && mode !== "feed" ? (
        <ConversationInput
          mode={mode}
          initialValue={mode === "chat"
            ? dashboardPresentation.chat.draft
            : mode === "outfit"
              ? dashboardPresentation.outfit.draft
              : dashboardPresentation.travel.draft}
          error={mode === "chat"
            ? dashboardPresentation.chat.error
            : mode === "outfit"
              ? dashboardPresentation.outfit.error
              : dashboardPresentation.travel.error}
          busy={mode === "chat"
            ? busy
            : busy || (mode === "outfit"
              ? dashboardPresentation.outfit.thinking
              : dashboardPresentation.travel.thinking)}
          thinking={mode === "chat"
            ? dashboardPresentation.chat.thinking
            : mode === "outfit"
              ? dashboardPresentation.outfit.thinking
              : dashboardPresentation.travel.thinking}
          retryable={mode === "chat" &&
            dashboardPresentation.chat.activeRequestKey !== null &&
            dashboardPresentation.chat.error !== null &&
            !dashboardPresentation.chat.thinking}
          pendingStatus={mode === "outfit"
            ? dashboardPresentation.outfit.pending?.status
            : mode === "travel"
              ? dashboardPresentation.travel.pending?.status
              : null}
          feedback={feedback}
          dispatch={dispatch}
          onDraftChange={onDraftChange}
          flushDraft={flushDraft}
          onLineCountChange={setComposerLineCount}
        />
      ) : null}
    </main>
  );
}
