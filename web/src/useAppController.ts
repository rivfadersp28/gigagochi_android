import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BridgeError,
  createNativeBridge,
  createUnavailableBridge,
  type GigagochiBridge,
} from "./bridge";
import {
  makeProductCommand,
  type AppLifecycleState,
  type AppSnapshot,
  type DashboardDraftMode,
  type DashboardMode,
  type JsonValue,
  type NavigationReadyPayload,
  type NotificationPermissionStatus,
  type PetTapFeedbackSnapshot,
  type ProductCommand,
  type ProductCommandType,
  type WebFeedbackKind,
  isAppSnapshot,
  isLifecycleChangedEventPayload,
  isPermissionChangedEventPayload,
  isSafeAreaSnapshot,
  isSystemBackEventPayload,
  isTravelShareCompletedEventPayload,
} from "./contracts";
import { createMockBridge } from "./mockBridge";
import { uuidV4 } from "./uuid";
import type { TravelVideoShareResult } from "./EventStoryTypes";

const MAX_SEEN_FEEDBACK_IDS = 256;
const MAX_SAFE_DISPATCH_RETRIES = 1;

interface ParsedSnapshotRevision {
  family: "r1" | "debug";
  runtimeId: string;
  sequence: number;
}

interface TravelShareWaiter {
  promise: Promise<TravelVideoShareResult>;
  resolve(result: TravelVideoShareResult): void;
  reject(reason: Error): void;
}

export function parseSnapshotRevision(revision: string): ParsedSnapshotRevision | null {
  const production = /^r1:([^:]+):(\d+):/.exec(revision);
  if (production) {
    const sequence = Number(production[2]);
    return Number.isSafeInteger(sequence)
      ? { family: "r1", runtimeId: production[1], sequence }
      : null;
  }
  const debug = /^debug-(\d+)$/.exec(revision);
  if (!debug) return null;
  const sequence = Number(debug[1]);
  return Number.isSafeInteger(sequence)
    ? { family: "debug", runtimeId: "debug", sequence }
    : null;
}

export function isOlderSnapshotRevision(candidate: string, current: string): boolean {
  const candidateRevision = parseSnapshotRevision(candidate);
  const currentRevision = parseSnapshotRevision(current);
  return Boolean(
    candidateRevision
    && currentRevision
    && candidateRevision.family === currentRevision.family
    && candidateRevision.runtimeId === currentRevision.runtimeId
    && candidateRevision.sequence < currentRevision.sequence,
  );
}

function isSafelyRetryableDispatchError(reason: unknown): boolean {
  return reason instanceof BridgeError
    && reason.retryable
    && (reason.code === "STATE_CONFLICT" || reason.code === "BRIDGE_TIMEOUT");
}

export interface AppController {
  snapshot: AppSnapshot | null;
  loading: boolean;
  error: string | null;
  dashboardMode: DashboardMode;
  createCustomOpen: boolean;
  createCustomValue: string;
  petTapFeedback: PetTapFeedbackSnapshot | null;
  lifecycleState: AppLifecycleState;
  storyChoicePending: { storyId: string; choice: string } | null;
  dispatch(type: ProductCommandType, payload?: JsonValue): Promise<void>;
  updateDashboardDraft(mode: DashboardDraftMode, value: string): void;
  flushDashboardDraft(mode: DashboardDraftMode, value?: string): Promise<void>;
  chooseStory(storyId: string, choice: string): Promise<void>;
  feedback(kind: WebFeedbackKind): void;
  openDashboardMode(mode: DashboardMode): void;
  closeDashboardMode(): void;
  openCreateCustom(): void;
  closeCreateCustom(): void;
  updateCreateCustom(value: string): void;
  markEventsViewed(viewedAt: number | null): void;
  shareTravelVideo(requestKey: string): Promise<TravelVideoShareResult>;
  retryBootstrap(): Promise<void>;
}

export function patchSnapshotInsets(
  snapshot: AppSnapshot | null,
  payload: unknown,
): AppSnapshot | null {
  if (!snapshot || !isSafeAreaSnapshot(payload)) return snapshot;
  return { ...snapshot, safeArea: payload };
}

export function patchSnapshotNotificationPermission(
  snapshot: AppSnapshot | null,
  status: NotificationPermissionStatus,
): AppSnapshot | null {
  if (!snapshot || snapshot.notificationPermission === status) return snapshot;
  return { ...snapshot, notificationPermission: status };
}

export function useAppController(bridgeOverride?: GigagochiBridge): AppController {
  const bridge = useMemo<GigagochiBridge>(() => {
    if (bridgeOverride) return bridgeOverride;
    const native = createNativeBridge();
    if (native) return native;
    if (import.meta.env.DEV || import.meta.env.MODE === "test") return createMockBridge();
    return createUnavailableBridge();
  }, [bridgeOverride]);
  const [snapshot, setSnapshot] = useState<AppSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dashboardMode, setDashboardMode] = useState<DashboardMode>("idle");
  const [createCustomOpen, setCreateCustomOpen] = useState(false);
  const [createCustomValue, setCreateCustomValue] = useState("");
  const [petTapFeedback, setPetTapFeedback] = useState<PetTapFeedbackSnapshot | null>(null);
  const [lifecycleState, setLifecycleState] = useState<AppLifecycleState>("foreground");
  const [storyChoicePending, setStoryChoicePending] = useState<{
    storyId: string;
    choice: string;
  } | null>(null);
  const snapshotRef = useRef<AppSnapshot | null>(null);
  const dispatchTailRef = useRef<Promise<void>>(Promise.resolve());
  const pendingDispatchCountRef = useRef(0);
  const seenFeedbackIdsRef = useRef(new Set<string>());
  const feedbackTimerRef = useRef<number | null>(null);
  const navigationSequenceRef = useRef(0);
  const navigationStateRef = useRef<NavigationReadyPayload>({
    canHandleBack: false,
    sequence: 0,
  });
  const notificationPermissionRequestedRef = useRef(false);
  const markedEventTimestampsRef = useRef(new Set<number>());
  const pendingEventTimestampsRef = useRef(new Set<number>());
  const storyChoicePendingRef = useRef<{ storyId: string; choice: string } | null>(null);
  const travelShareWaitersRef = useRef(new Map<string, TravelShareWaiter>());
  const pendingDashboardDraftsRef = useRef(new Map<DashboardDraftMode, {
    mode: DashboardDraftMode;
    value: string;
    version: number;
  }>());
  const dashboardDraftVersionRef = useRef(0);
  const dashboardDraftWorkerRef = useRef<Promise<void> | null>(null);
  const ensureDashboardDraftWorkerRef = useRef<() => Promise<void>>(
    () => Promise.resolve(),
  );

  const clearStoryChoicePending = useCallback((storyId: string) => {
    if (storyChoicePendingRef.current?.storyId !== storyId) return;
    storyChoicePendingRef.current = null;
    setStoryChoicePending(null);
  }, []);

  const commitSnapshot = useCallback((next: AppSnapshot) => {
    const current = snapshotRef.current;
    if (current && isOlderSnapshotRevision(next.revision, current.revision)) return false;
    snapshotRef.current = next;
    setSnapshot(next);
    setDashboardMode(next.dashboardMode);
    const pendingStoryChoice = storyChoicePendingRef.current;
    if (
      pendingStoryChoice &&
      (
        next.story?.story.storyId !== pendingStoryChoice.storyId ||
        next.story.phase === "choicePending" ||
        next.story.phase === "result" ||
        next.story.phase === "retryable"
      )
    ) {
      storyChoicePendingRef.current = null;
      setStoryChoicePending(null);
    }
    const feedback = next.petTapFeedback;
    if (!feedback || seenFeedbackIdsRef.current.has(feedback.eventId)) return true;
    seenFeedbackIdsRef.current.add(feedback.eventId);
    if (seenFeedbackIdsRef.current.size > MAX_SEEN_FEEDBACK_IDS) {
      const oldest = seenFeedbackIdsRef.current.values().next().value;
      if (oldest !== undefined) seenFeedbackIdsRef.current.delete(oldest);
    }
    if (!feedback.thanks) return true;
    setPetTapFeedback(feedback);
    if (feedbackTimerRef.current !== null) window.clearTimeout(feedbackTimerRef.current);
    feedbackTimerRef.current = window.setTimeout(() => {
      setPetTapFeedback((current) => current?.eventId === feedback.eventId ? null : current);
      feedbackTimerRef.current = null;
    }, feedback.visibleMillis);
    return true;
  }, []);

  const retryBootstrap = useCallback(async () => {
    await dispatchTailRef.current;
    setLoading(true);
    setError(null);
    try {
      const next = await bridge.bootstrap();
      commitSnapshot(next);
      setCreateCustomOpen(false);
      setCreateCustomValue("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "BOOTSTRAP_FAILED");
    } finally {
      setLoading(false);
    }
  }, [bridge, commitSnapshot]);

  useEffect(() => {
    void retryBootstrap();
  }, [retryBootstrap]);

  useEffect(() => {
    const receiveNativeEvent = (rawEvent: Event) => {
      const event = rawEvent as CustomEvent<{ type?: unknown; payload?: unknown }>;
      if (event.detail?.type === "stateChanged" && isAppSnapshot(event.detail.payload)) {
        commitSnapshot(event.detail.payload);
      } else if (event.detail?.type === "insetsChanged") {
        const next = patchSnapshotInsets(snapshotRef.current, event.detail.payload);
        if (next && next !== snapshotRef.current) commitSnapshot(next);
      } else if (
        event.detail?.type === "permissionChanged" &&
        isPermissionChangedEventPayload(event.detail.payload)
      ) {
        const next = patchSnapshotNotificationPermission(
          snapshotRef.current,
          event.detail.payload.status,
        );
        if (next && next !== snapshotRef.current) commitSnapshot(next);
      } else if (
        event.detail?.type === "lifecycleChanged" &&
        isLifecycleChangedEventPayload(event.detail.payload)
      ) {
        setLifecycleState(event.detail.payload.state);
      } else if (
        event.detail?.type === "travelShareCompleted" &&
        isTravelShareCompletedEventPayload(event.detail.payload)
      ) {
        const payload = event.detail.payload;
        const waiter = travelShareWaitersRef.current.get(payload.requestKey);
        if (!waiter) return;
        travelShareWaitersRef.current.delete(payload.requestKey);
        if (payload.status === "opened") {
          waiter.resolve("opened");
        } else {
          waiter.reject(new Error("TRAVEL_SHARE_FAILED"));
        }
      }
    };
    const recoverEventGap = () => {
      void retryBootstrap();
    };
    window.addEventListener("gigagochi:native-event", receiveNativeEvent);
    window.addEventListener("gigagochi:native-event-gap", recoverEventGap);
    return () => {
      window.removeEventListener("gigagochi:native-event", receiveNativeEvent);
      window.removeEventListener("gigagochi:native-event-gap", recoverEventGap);
    };
  }, [commitSnapshot, retryBootstrap]);

  useEffect(() => () => {
    if (feedbackTimerRef.current !== null) {
      window.clearTimeout(feedbackTimerRef.current);
      feedbackTimerRef.current = null;
    }
  }, []);

  useEffect(() => () => {
    const pending = [...travelShareWaitersRef.current.values()];
    travelShareWaitersRef.current.clear();
    pending.forEach((waiter) => waiter.reject(new Error("TRAVEL_SHARE_CANCELLED")));
  }, [bridge]);

  const enqueueDispatch = useCallback(
    (type: ProductCommandType, payload: JsonValue = {}, showLoading = true) => {
      if (showLoading) {
        pendingDispatchCountRef.current += 1;
        setLoading(true);
      }
      const execute = async () => {
        const current = snapshotRef.current;
        if (!current) throw new Error("BRIDGE_NOT_READY");
        setError(null);
        let command: ProductCommand = makeProductCommand(type, payload, current.revision);
        let retries = 0;
        let next: AppSnapshot;
        while (true) {
          try {
            next = await bridge.dispatch(command);
            break;
          } catch (reason) {
            if (
              retries >= MAX_SAFE_DISPATCH_RETRIES
              || !isSafelyRetryableDispatchError(reason)
            ) {
              throw reason;
            }
            retries += 1;
            const recovered = reason instanceof BridgeError && reason.snapshot
              ? reason.snapshot
              : await bridge.bootstrap();
            commitSnapshot(recovered);
            const authoritative = snapshotRef.current ?? recovered;
            command = {
              ...command,
              expectedSnapshotRevision: authoritative.revision,
            };
          }
        }
        commitSnapshot(next);
        if (type === "CREATE_ANSWER") {
          setCreateCustomOpen(false);
          setCreateCustomValue("");
        }
      };
      const operation = dispatchTailRef.current.then(execute, execute);
      dispatchTailRef.current = operation.then(() => undefined, () => undefined);
      const exposedOperation = operation.finally(() => {
        if (showLoading) {
          pendingDispatchCountRef.current -= 1;
          if (pendingDispatchCountRef.current === 0) setLoading(false);
        }
      });
      // A caller may intentionally fire-and-forget. Attaching a side handler keeps
      // that usage safe while the returned promise still communicates rejection.
      void exposedOperation.catch(() => undefined);
      return exposedOperation;
    },
    [bridge, commitSnapshot],
  );
  const dispatch = useCallback(
    (type: ProductCommandType, payload: JsonValue = {}) => (
      enqueueDispatch(type, payload, true)
    ),
    [enqueueDispatch],
  );

  const ensureDashboardDraftWorker = useCallback((): Promise<void> => {
    const active = dashboardDraftWorkerRef.current;
    if (active) return active;
    const worker = (async () => {
      while (pendingDashboardDraftsRef.current.size > 0) {
        const candidate = [...pendingDashboardDraftsRef.current.values()]
          .sort((left, right) => left.version - right.version)[0];
        if (!candidate) return;
        const currentCandidate = pendingDashboardDraftsRef.current.get(candidate.mode);
        if (currentCandidate?.version === candidate.version) {
          pendingDashboardDraftsRef.current.delete(candidate.mode);
        }
        try {
          await enqueueDispatch("DASHBOARD_UPDATE_DRAFT", {
            mode: candidate.mode,
            value: candidate.value,
          }, false);
        } catch (reason) {
          if (!pendingDashboardDraftsRef.current.has(candidate.mode)) {
            pendingDashboardDraftsRef.current.set(candidate.mode, candidate);
          }
          throw reason;
        }
      }
    })();
    dashboardDraftWorkerRef.current = worker;
    void worker.then(
      () => {
        if (dashboardDraftWorkerRef.current === worker) {
          dashboardDraftWorkerRef.current = null;
        }
        if (pendingDashboardDraftsRef.current.size > 0) {
          void ensureDashboardDraftWorkerRef.current().catch(() => undefined);
        }
      },
      () => {
        if (dashboardDraftWorkerRef.current === worker) {
          dashboardDraftWorkerRef.current = null;
        }
      },
    );
    return worker;
  }, [enqueueDispatch]);
  ensureDashboardDraftWorkerRef.current = ensureDashboardDraftWorker;

  const updateDashboardDraft = useCallback((mode: DashboardDraftMode, rawValue: string) => {
    const value = rawValue.slice(0, 1_000);
    const pending = pendingDashboardDraftsRef.current.get(mode);
    if (pending?.value === value) {
      void ensureDashboardDraftWorkerRef.current().catch(() => undefined);
      return;
    }
    dashboardDraftVersionRef.current += 1;
    pendingDashboardDraftsRef.current.set(mode, {
      mode,
      value,
      version: dashboardDraftVersionRef.current,
    });
    void ensureDashboardDraftWorkerRef.current().catch(() => undefined);
  }, []);

  const flushDashboardDraft = useCallback(async (
    mode: DashboardDraftMode,
    value?: string,
  ) => {
    if (value !== undefined) updateDashboardDraft(mode, value);
    while (
      pendingDashboardDraftsRef.current.has(mode) ||
      dashboardDraftWorkerRef.current !== null
    ) {
      await ensureDashboardDraftWorkerRef.current();
    }
  }, [updateDashboardDraft]);
  const feedback = useCallback((kind: WebFeedbackKind) => {
    void bridge.call("feedback", { kind, eventId: uuidV4() }).catch(() => undefined);
  }, [bridge]);

  const chooseStory = useCallback((storyId: string, choice: string) => {
    if (storyChoicePendingRef.current) return Promise.resolve();
    const pending = { storyId, choice };
    storyChoicePendingRef.current = pending;
    setStoryChoicePending(pending);
    return dispatch("STORY_CHOOSE", { storyId, choice }).catch((reason: unknown) => {
      clearStoryChoicePending(storyId);
      throw reason;
    });
  }, [clearStoryChoicePending, dispatch]);

  const closeDashboardMode = useCallback(() => {
    const mode = dashboardMode;
    const close = async () => {
      try {
        if (mode === "chat" || mode === "outfit" || mode === "travel") {
          await flushDashboardDraft(mode);
        }
        setDashboardMode("idle");
        await dispatch("DASHBOARD_CLOSE_MODE");
      } catch {
        setDashboardMode(snapshotRef.current?.dashboardMode ?? mode);
      }
    };
    void close();
  }, [dashboardMode, dispatch, flushDashboardDraft]);

  const closeCreateCustom = useCallback(() => {
    setCreateCustomOpen(false);
    setCreateCustomValue("");
  }, []);

  const markEventsViewed = useCallback((viewedAt: number | null) => {
    if (
      viewedAt === null ||
      markedEventTimestampsRef.current.has(viewedAt) ||
      pendingEventTimestampsRef.current.has(viewedAt)
    ) return;
    pendingEventTimestampsRef.current.add(viewedAt);
    void dispatch("EVENTS_MARK_VIEWED", { viewedAt })
      .then(
        () => markedEventTimestampsRef.current.add(viewedAt),
        () => undefined,
      )
      .finally(() => pendingEventTimestampsRef.current.delete(viewedAt));
  }, [dispatch]);

  const shareTravelVideo = useCallback((
    requestKey: string,
  ): Promise<TravelVideoShareResult> => {
    const existing = travelShareWaitersRef.current.get(requestKey);
    if (existing) return existing.promise;

    let resolve!: (result: TravelVideoShareResult) => void;
    let reject!: (reason: Error) => void;
    const promise = new Promise<TravelVideoShareResult>((resolvePromise, rejectPromise) => {
      resolve = resolvePromise;
      reject = rejectPromise;
    });
    const waiter: TravelShareWaiter = { promise, resolve, reject };
    travelShareWaitersRef.current.set(requestKey, waiter);

    const rejectIfPending = (reason: unknown) => {
      if (travelShareWaitersRef.current.get(requestKey) !== waiter) return;
      travelShareWaitersRef.current.delete(requestKey);
      waiter.reject(reason instanceof Error ? reason : new Error("TRAVEL_SHARE_FAILED"));
    };
    try {
      void bridge.call("shareTravelVideo", { requestKey }).then(
        (result) => {
          if (result !== "accepted") rejectIfPending(new Error("INVALID_SHARE_RESULT"));
        },
        rejectIfPending,
      );
    } catch (reason) {
      rejectIfPending(reason);
    }
    return promise;
  }, [bridge]);

  const canHandleWebBack = snapshot !== null && (
    (snapshot.route === "create" && createCustomOpen) ||
    (snapshot.route === "dashboard" && dashboardMode !== "idle") ||
    snapshot.route === "events" ||
    snapshot.route === "story"
  );

  useEffect(() => {
    if (
      !snapshot ||
      snapshot.notificationPermission !== "unknown" ||
      (snapshot.route !== "create" && snapshot.route !== "dashboard") ||
      notificationPermissionRequestedRef.current
    ) {
      return;
    }
    notificationPermissionRequestedRef.current = true;
    void bridge.call("requestNotificationPermission", {}).catch(() => undefined);
  }, [bridge, snapshot?.notificationPermission, snapshot?.route]);

  useEffect(() => {
    if (!snapshot) return;
    navigationSequenceRef.current += 1;
    const payload: NavigationReadyPayload = {
      canHandleBack: canHandleWebBack,
      sequence: navigationSequenceRef.current,
    };
    navigationStateRef.current = payload;
    void bridge.call("navigationReady", {
      canHandleBack: payload.canHandleBack,
      sequence: payload.sequence,
    }).catch(() => undefined);
  }, [bridge, canHandleWebBack, snapshot?.route]);

  useEffect(() => {
    const receiveSystemBack = (rawEvent: Event) => {
      const event = rawEvent as CustomEvent<{ type?: unknown; payload?: unknown }>;
      if (
        event.detail?.type !== "systemBack" ||
        !isSystemBackEventPayload(event.detail.payload)
      ) {
        return;
      }
      const navigationState = navigationStateRef.current;
      if (
        !navigationState.canHandleBack ||
        event.detail.payload.navigationSequence !== navigationState.sequence
      ) {
        return;
      }
      navigationStateRef.current = { ...navigationState, canHandleBack: false };
      if (snapshotRef.current?.route === "create" && createCustomOpen) {
        closeCreateCustom();
      } else if (snapshotRef.current?.route === "dashboard" && dashboardMode !== "idle") {
        closeDashboardMode();
      } else if (
        snapshotRef.current?.route === "events" ||
        snapshotRef.current?.route === "story"
      ) {
        void dispatch("BACK");
      }
    };
    window.addEventListener("gigagochi:native-event", receiveSystemBack);
    return () => window.removeEventListener("gigagochi:native-event", receiveSystemBack);
  }, [closeCreateCustom, closeDashboardMode, createCustomOpen, dashboardMode, dispatch]);

  return {
    snapshot,
    loading,
    error,
    dashboardMode,
    createCustomOpen,
    createCustomValue,
    petTapFeedback,
    lifecycleState,
    storyChoicePending,
    dispatch,
    updateDashboardDraft,
    flushDashboardDraft,
    chooseStory,
    feedback,
    openDashboardMode: (mode) => {
      if (mode === "idle") return;
      setDashboardMode(mode);
      void dispatch("DASHBOARD_OPEN_MODE", { mode });
    },
    closeDashboardMode,
    openCreateCustom: () => {
      setCreateCustomOpen(true);
      setCreateCustomValue("");
    },
    closeCreateCustom,
    updateCreateCustom: (value) => setCreateCustomValue(value.slice(0, 300)),
    markEventsViewed,
    shareTravelVideo,
    retryBootstrap,
  };
}
