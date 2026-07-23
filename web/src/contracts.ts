import { uuidV4 } from "./uuid";
import type {
  WebEventsSnapshot,
  WebOpenedStorySnapshot,
} from "./EventStoryTypes";

export const BRIDGE_PROTOCOL_VERSION = 1 as const;
export const WEB_BUNDLE_VERSION = "0.1.0";
export const BRIDGE_SCHEMA_HASH = "gigagochi-bridge-v3-d76ce0d6b3f08c5351e979e8a6416a60a2308befcf72c1b531d4325d82691280";

export type AppRoute =
  | "create"
  | "dashboard"
  | "events"
  | "story"
  | "connectionError"
  | "localDataError";

export type NavigationRoute = "events" | "travel";

export type DashboardMode = "idle" | "chat" | "feed" | "outfit" | "travel";
export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export type BridgeMethod =
  | "bootstrap"
  | "dispatch"
  | "requestNotificationPermission"
  | "shareTravelVideo"
  | "feedback"
  | "navigationReady";

export type WebFeedbackKind =
  | "createAnswer"
  | "createCustom"
  | "createRetry"
  | "dashboardAction"
  | "chatSubmit"
  | "buttonPress";

export type BridgeErrorCode =
  | "BAD_MESSAGE"
  | "UNSUPPORTED_PROTOCOL"
  | "UNSUPPORTED_METHOD"
  | "INVALID_PAYLOAD"
  | "PAYLOAD_TOO_LARGE"
  | "RATE_LIMITED"
  | "BRIDGE_QUEUE_FULL"
  | "BRIDGE_NOT_READY"
  | "STALE_DOCUMENT"
  | "STATE_CONFLICT"
  | "WRONG_STAGE"
  | "NOT_FOUND"
  | "OFFLINE_NOT_QUEUED"
  | "PERMISSION_DENIED"
  | "SHARE_UNAVAILABLE"
  | "LOCAL_DATA_ERROR"
  | "INTERNAL";

export interface BridgeRequest {
  kind: "request";
  protocolVersion: typeof BRIDGE_PROTOCOL_VERSION;
  documentId: string;
  bridgeSessionId?: string;
  requestId: string;
  method: BridgeMethod;
  payload: JsonValue;
}

export type BridgeResponse =
  | {
      kind: "response";
      protocolVersion: typeof BRIDGE_PROTOCOL_VERSION;
      documentId: string;
      bridgeSessionId: string;
      requestId: string;
      ok: true;
      result: JsonValue;
      error: null;
    }
  | {
      kind: "response";
      protocolVersion: typeof BRIDGE_PROTOCOL_VERSION;
      documentId: string;
      bridgeSessionId: string | null;
      requestId: string;
      ok: false;
      result: AppSnapshot | null;
      error: { code: string; retryable: boolean };
    };

export interface BridgeEvent {
  kind: "event";
  protocolVersion: typeof BRIDGE_PROTOCOL_VERSION;
  documentId: string;
  bridgeSessionId: string;
  subscriptionId: "app-state";
  sequence: number;
  type:
    | "stateChanged"
    | "insetsChanged"
    | "permissionChanged"
    | "lifecycleChanged"
    | "systemBack"
    | "travelShareCompleted";
  payload: JsonValue;
}

export interface NavigationReadyPayload {
  canHandleBack: boolean;
  sequence: number;
}

export interface SystemBackEventPayload {
  navigationSequence: number;
}

export type NotificationPermissionStatus = "unknown" | "granted" | "denied";
export type AppLifecycleState = "foreground" | "background";

export interface PermissionChangedEventPayload {
  status: NotificationPermissionStatus;
}

export interface LifecycleChangedEventPayload {
  state: AppLifecycleState;
}

export interface TravelShareCompletedEventPayload {
  requestKey: string;
  status: "opened" | "failed";
}

export interface SafeAreaSnapshot {
  top: number;
  right: number;
  bottom: number;
  left: number;
  imeTop: number;
  imeHeight: number;
  imeProgress: number;
}

export interface PetMediaSnapshot {
  videoRef: string | null;
  posterRef: string | null;
  sadVideoRef: string | null;
  happyVideoRef: string | null;
}

export interface PetSnapshot {
  name: string;
  stageLabel: string;
  experience: number;
  hunger: number;
  happiness: number;
  energy: number;
  message: string;
  petTapProgress: number;
  media: PetMediaSnapshot;
}

export interface CreateQuestionSnapshot {
  title: string;
  options: string[];
}

export interface CreateSnapshot {
  step: number;
  title: string;
  options: string[];
  nextQuestion: CreateQuestionSnapshot | null;
  phase: "initial" | "transition" | "formed";
  generation: "idle" | "running" | "ready" | "retryable" | "failed";
  error: string | null;
  retryTarget: "persistence" | "generation" | "finalization" | null;
}

export interface FirstSessionSnapshot {
  stage:
    | "awaiting-chat"
    | "awaiting-chat-followup"
    | "awaiting-first-food"
    | "awaiting-remedy"
    | "awaiting-travel"
    | "confirming-travel"
    | "awaiting-completion-message"
    | "completed";
  allowedAction: "chat" | "feed" | "travel" | "outfit" | null;
  messagePortions: string[];
  selectedDestination: string | null;
}

export interface PetTapFeedbackSnapshot {
  eventId: string;
  rewarded: boolean;
  thanks: string | null;
  visibleMillis: number;
}

export type DashboardReplySource =
  | "chat"
  | "feed"
  | "transient"
  | "firstSession"
  | "settled";

export interface DashboardReplySnapshot {
  source: DashboardReplySource;
  requestKey: string;
  portions: string[];
  portionIndex: number;
  hasNextPortion: boolean;
  autoAdvanceDelayMillis: number;
}

export interface DashboardChatSnapshot {
  draft: string;
  error: string | null;
  activeRequestKey: string | null;
  queuedRequestKey: string | null;
  thinking: boolean;
}

export type DashboardFood = "berry-bowl" | "leaf-crunch";

export interface DashboardFeedSnapshot {
  error: string | null;
  activeRequestKey: string | null;
  activeFood: DashboardFood | null;
  audioIndex: number | null;
  pulseId: number;
  thinking: boolean;
}

export interface DashboardOutfitPendingSnapshot {
  requestKey: string;
  status: PendingOperationStatus;
  prompt: string;
  displayItem: string;
  experienceCost: number;
}

export interface DashboardOutfitSnapshot {
  draft: string;
  error: string | null;
  activeRequestKey: string | null;
  thinking: boolean;
  experienceCost: number;
  pending: DashboardOutfitPendingSnapshot | null;
}

export interface DashboardTravelPendingSnapshot {
  requestKey: string;
  status: PendingOperationStatus;
  prompt: string;
}

export interface DashboardTravelSnapshot {
  draft: string;
  error: string | null;
  activeRequestKey: string | null;
  thinking: boolean;
  pending: DashboardTravelPendingSnapshot | null;
}

export interface DashboardSnapshot {
  reply: DashboardReplySnapshot | null;
  chat: DashboardChatSnapshot;
  feed: DashboardFeedSnapshot;
  outfit: DashboardOutfitSnapshot;
  travel: DashboardTravelSnapshot;
}

export type PendingOperationStatus =
  | "pending"
  | "dispatching"
  | "attached"
  | "ready"
  | "retryable"
  | "outcomeUnknown"
  | "failed"
  | "applyConflict"
  | "completed";

export interface PendingOperationSnapshot {
  requestKey: string;
  status: PendingOperationStatus;
  prompt: string | null;
}

export interface PendingOperationsSnapshot {
  chat: PendingOperationSnapshot | null;
  outfit: PendingOperationSnapshot | null;
  travel: PendingOperationSnapshot | null;
}

export interface BridgeCapabilities {
  requestNotificationPermission: boolean;
  shareTravelVideo: boolean;
  feedback: boolean;
  navigationReady: boolean;
  opaqueMedia: boolean;
}

export interface AppSnapshot {
  protocolVersion: typeof BRIDGE_PROTOCOL_VERSION;
  appVersion: string;
  webBundleVersion: string;
  revision: string;
  route: AppRoute;
  dashboardMode: DashboardMode;
  capabilities: BridgeCapabilities;
  pendingDeepLinkTarget: "dashboard" | "events" | "story" | null;
  reducedMotion: boolean;
  safeArea: SafeAreaSnapshot;
  notificationPermission: NotificationPermissionStatus;
  create: CreateSnapshot | null;
  pet: PetSnapshot | null;
  firstSession: FirstSessionSnapshot | null;
  dashboard: DashboardSnapshot | null;
  pending: PendingOperationsSnapshot;
  petTapFeedback: PetTapFeedbackSnapshot | null;
  events: WebEventsSnapshot | null;
  story: WebOpenedStorySnapshot | null;
}

interface CommandFence {
  requestKey: string;
  expectedSnapshotRevision: string;
}

export type ProductCommand =
  | (CommandFence & { type: "CREATE_ANSWER"; payload: { answer: string; step: number } })
  | (CommandFence & { type: "CREATE_BACKGROUND_COMPLETE"; payload: Record<string, never> })
  | (CommandFence & { type: "CREATE_RETRY"; payload: Record<string, never> })
  | (CommandFence & { type: "CREATE_FINISH"; payload: Record<string, never> })
  | (CommandFence & { type: "CHAT_SEND"; payload: { message: string } })
  | (CommandFence & { type: "CHAT_RETRY"; payload: Record<string, never> })
  | (CommandFence & {
      type: "DASHBOARD_OPEN_MODE";
      payload: { mode: Exclude<DashboardMode, "idle"> };
    })
  | (CommandFence & { type: "DASHBOARD_CLOSE_MODE"; payload: Record<string, never> })
  | (CommandFence & {
      type: "DASHBOARD_UPDATE_DRAFT";
      payload: { mode: DashboardDraftMode; value: string };
    })
  | (CommandFence & { type: "REPLY_ADVANCE"; payload: { requestKey: string } })
  | (CommandFence & { type: "REPLY_COMPLETE"; payload: { requestKey: string } })
  | (CommandFence & { type: "CHAT_REPLY_PRESENTED"; payload: { requestKey: string } })
  | (CommandFence & {
      type: "FEED_CONSUME";
      payload: { food: DashboardFood };
    })
  | (CommandFence & { type: "OUTFIT_SUBMIT"; payload: { prompt: string } })
  | (CommandFence & { type: "OUTFIT_RETRY"; payload: Record<string, never> })
  | (CommandFence & { type: "TRAVEL_SUBMIT"; payload: { prompt: string } })
  | (CommandFence & { type: "TRAVEL_RETRY"; payload: Record<string, never> })
  | (CommandFence & { type: "STORY_OPEN"; payload: { storyId: string } })
  | (CommandFence & { type: "STORY_CHOOSE"; payload: { storyId: string; choice: string } })
  | (CommandFence & { type: "STORY_RETRY"; payload: { storyId: string } })
  | (CommandFence & { type: "STORY_FINISH"; payload: { storyId: string } })
  | (CommandFence & { type: "EVENTS_MARK_VIEWED"; payload: { viewedAt: number } })
  | (CommandFence & { type: "PET_TAP"; payload: Record<string, never> })
  | (CommandFence & { type: "NAVIGATE"; payload: { route: NavigationRoute } })
  | (CommandFence & { type: "BACK"; payload: Record<string, never> });

export type ProductCommandType = ProductCommand["type"];
export type DashboardDraftMode = Exclude<DashboardMode, "idle" | "feed">;

export function makeProductCommand(
  type: ProductCommandType,
  rawPayload: JsonValue,
  expectedSnapshotRevision: string,
): ProductCommand {
  const payload = asObject(rawPayload);
  const fence: CommandFence = {
    requestKey: uuidV4(),
    expectedSnapshotRevision,
  };
  switch (type) {
    case "CREATE_ANSWER":
      return {
        ...fence,
        type,
        payload: {
          answer: requiredString(payload.answer),
          step: requiredIntegerInRange(payload.step, 0, 4),
        },
      };
    case "CHAT_SEND":
      return { ...fence, type, payload: { message: requiredString(payload.message) } };
    case "CHAT_RETRY":
      if (!hasExactKeys(payload, [])) throw new Error("INVALID_PAYLOAD");
      return { ...fence, type, payload: {} };
    case "DASHBOARD_OPEN_MODE": {
      const mode = requiredString(payload.mode);
      if (!isDashboardMode(mode) || mode === "idle") throw new Error("INVALID_PAYLOAD");
      return { ...fence, type, payload: { mode } };
    }
    case "DASHBOARD_UPDATE_DRAFT": {
      if (!hasExactKeys(payload, ["mode", "value"])) throw new Error("INVALID_PAYLOAD");
      const mode = payload.mode;
      const value = payload.value;
      if (
        (mode !== "chat" && mode !== "outfit" && mode !== "travel") ||
        typeof value !== "string" ||
        value.length > 1_000
      ) {
        throw new Error("INVALID_PAYLOAD");
      }
      return { ...fence, type, payload: { mode, value } };
    }
    case "REPLY_ADVANCE":
    case "REPLY_COMPLETE":
    case "CHAT_REPLY_PRESENTED":
      return { ...fence, type, payload: { requestKey: requiredString(payload.requestKey) } };
    case "FEED_CONSUME": {
      const food = requiredString(payload.food);
      if (food !== "berry-bowl" && food !== "leaf-crunch") throw new Error("INVALID_PAYLOAD");
      return { ...fence, type, payload: { food } };
    }
    case "OUTFIT_SUBMIT":
    case "TRAVEL_SUBMIT":
      return { ...fence, type, payload: { prompt: requiredString(payload.prompt) } };
    case "STORY_CHOOSE":
      return {
        ...fence,
        type,
        payload: {
          storyId: requiredString(payload.storyId),
          choice: requiredString(payload.choice),
        },
      };
    case "STORY_OPEN":
    case "STORY_RETRY":
    case "STORY_FINISH":
      return { ...fence, type, payload: { storyId: requiredString(payload.storyId) } };
    case "EVENTS_MARK_VIEWED":
      return { ...fence, type, payload: { viewedAt: requiredNumber(payload.viewedAt) } };
    case "NAVIGATE": {
      const route = requiredString(payload.route);
      if (route !== "events" && route !== "travel") throw new Error("INVALID_PAYLOAD");
      return { ...fence, type, payload: { route } };
    }
    case "CREATE_BACKGROUND_COMPLETE":
    case "CREATE_RETRY":
    case "CREATE_FINISH":
    case "DASHBOARD_CLOSE_MODE":
    case "OUTFIT_RETRY":
    case "TRAVEL_RETRY":
    case "PET_TAP":
    case "BACK":
      return { ...fence, type, payload: {} };
  }
}

export function isBridgeResponse(value: unknown): value is BridgeResponse {
  if (!isRecord(value) || !hasExactKeys(value, [
    "kind",
    "protocolVersion",
    "documentId",
    "bridgeSessionId",
    "requestId",
    "ok",
    "result",
    "error",
  ])) return false;
  if (
    value.kind !== "response" ||
    value.protocolVersion !== BRIDGE_PROTOCOL_VERSION ||
    typeof value.documentId !== "string" ||
    typeof value.requestId !== "string" ||
    typeof value.ok !== "boolean"
  ) {
    return false;
  }
  if (value.ok) {
    return typeof value.bridgeSessionId === "string" &&
      isJsonValue(value.result) &&
      value.error === null;
  }
  return (
    (value.bridgeSessionId === null || typeof value.bridgeSessionId === "string") &&
    isRecord(value.error) &&
    hasExactKeys(value.error, ["code", "retryable"]) &&
    typeof value.error.code === "string" &&
    typeof value.error.retryable === "boolean" &&
    (
      value.result === null ||
      (value.error.code === "STATE_CONFLICT" && isAppSnapshot(value.result))
    )
  );
}

export function isBridgeEvent(value: unknown): value is BridgeEvent {
  return (
    isRecord(value) &&
    hasExactKeys(value, [
      "kind",
      "protocolVersion",
      "documentId",
      "bridgeSessionId",
      "subscriptionId",
      "sequence",
      "type",
      "payload",
    ]) &&
    value.kind === "event" &&
    value.protocolVersion === BRIDGE_PROTOCOL_VERSION &&
    typeof value.documentId === "string" &&
    typeof value.bridgeSessionId === "string" &&
    value.subscriptionId === "app-state" &&
    isNonNegativeSafeInteger(value.sequence) &&
    [
      "stateChanged",
      "insetsChanged",
      "permissionChanged",
      "lifecycleChanged",
      "systemBack",
      "travelShareCompleted",
    ].includes(String(value.type)) &&
    isJsonValue(value.payload)
  );
}

export function isSystemBackEventPayload(value: unknown): value is SystemBackEventPayload {
  return isRecord(value) &&
    hasExactKeys(value, ["navigationSequence"]) &&
    Number.isSafeInteger(value.navigationSequence) &&
    Number(value.navigationSequence) >= 0;
}

export function isPermissionChangedEventPayload(
  value: unknown,
): value is PermissionChangedEventPayload {
  return isRecord(value) &&
    hasExactKeys(value, ["status"]) &&
    (value.status === "unknown" || value.status === "granted" || value.status === "denied");
}

export function isLifecycleChangedEventPayload(
  value: unknown,
): value is LifecycleChangedEventPayload {
  return isRecord(value) &&
    hasExactKeys(value, ["state"]) &&
    (value.state === "foreground" || value.state === "background");
}

export function isTravelShareCompletedEventPayload(
  value: unknown,
): value is TravelShareCompletedEventPayload {
  return isRecord(value) &&
    hasExactKeys(value, ["requestKey", "status"]) &&
    typeof value.requestKey === "string" &&
    value.requestKey.length > 0 &&
    (value.status === "opened" || value.status === "failed");
}

export function isAppSnapshot(value: unknown): value is AppSnapshot {
  if (!isRecord(value) || !hasExactKeys(value, [
    "protocolVersion",
    "appVersion",
    "webBundleVersion",
    "revision",
    "route",
    "dashboardMode",
    "capabilities",
    "pendingDeepLinkTarget",
    "reducedMotion",
    "safeArea",
    "notificationPermission",
    "create",
    "pet",
    "firstSession",
    "dashboard",
    "events",
    "story",
    "pending",
    "petTapFeedback",
  ])) return false;
  if (
    value.protocolVersion !== BRIDGE_PROTOCOL_VERSION ||
    typeof value.appVersion !== "string" ||
    typeof value.webBundleVersion !== "string" ||
    typeof value.revision !== "string" ||
    !isAppRoute(value.route) ||
    !isDashboardMode(value.dashboardMode) ||
    !isBridgeCapabilities(value.capabilities) ||
    !(
      value.pendingDeepLinkTarget === null ||
      value.pendingDeepLinkTarget === "dashboard" ||
      value.pendingDeepLinkTarget === "events" ||
      value.pendingDeepLinkTarget === "story"
    ) ||
    typeof value.reducedMotion !== "boolean" ||
    !isSafeAreaSnapshot(value.safeArea) ||
    !["unknown", "granted", "denied"].includes(String(value.notificationPermission))
  ) {
    return false;
  }
  return (value.create === null || isCreateSnapshot(value.create)) &&
    (value.pet === null || isPetSnapshot(value.pet)) &&
    (value.firstSession === null || isFirstSessionSnapshot(value.firstSession)) &&
    (value.dashboard === null || isDashboardSnapshot(value.dashboard)) &&
    isPendingOperationsSnapshot(value.pending) &&
    (value.petTapFeedback === null || isPetTapFeedbackSnapshot(value.petTapFeedback)) &&
    (value.events === null || isWebEventsSnapshot(value.events)) &&
    (value.story === null || isWebOpenedStorySnapshot(value.story));
}

function isBridgeCapabilities(value: unknown): value is BridgeCapabilities {
  return isRecord(value) &&
    hasExactKeys(value, [
      "requestNotificationPermission",
      "shareTravelVideo",
      "feedback",
      "navigationReady",
      "opaqueMedia",
    ]) &&
    typeof value.requestNotificationPermission === "boolean" &&
    typeof value.shareTravelVideo === "boolean" &&
    typeof value.feedback === "boolean" &&
    typeof value.navigationReady === "boolean" &&
    typeof value.opaqueMedia === "boolean";
}

const OPAQUE_MEDIA_REFERENCE_PATTERN = /^\/media\/v1\/[a-f0-9]{32}\/[a-f0-9]{32}$/;
const PACKAGED_MEDIA_REFERENCE_PATTERN = /^\/(assets|res)\/[A-Za-z0-9][A-Za-z0-9._/-]*$/;

function isWebMediaReference(value: unknown): value is string {
  if (typeof value !== "string") return false;
  if (OPAQUE_MEDIA_REFERENCE_PATTERN.test(value)) return true;
  return PACKAGED_MEDIA_REFERENCE_PATTERN.test(value) &&
    value.split("/").slice(2).every((segment) => (
      segment.length > 0 && segment !== "." && segment !== ".."
    ));
}

function isNullableWebMediaReference(value: unknown): value is string | null {
  return value === null || isWebMediaReference(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && actual.every((key) => keys.includes(key));
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string");
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isScheduledStoryResultSnapshot(value: unknown): boolean {
  if (!isRecord(value) || !hasExactKeys(value, [
    "text",
    "reaction",
    "consequence",
    "experienceGained",
  ])) return false;
  return typeof value.text === "string" &&
    typeof value.reaction === "string" &&
    typeof value.consequence === "string" &&
    isNonNegativeSafeInteger(value.experienceGained);
}

function isScheduledStoryEventSnapshot(value: unknown): boolean {
  if (!isRecord(value) || !hasExactKeys(value, ["story"]) || !isRecord(value.story)) {
    return false;
  }
  const story = value.story;
  if (!hasExactKeys(story, [
    "storyId",
    "title",
    "text",
    "question",
    "choices",
    "createdAt",
    "imageRef",
    "videoRef",
    "selectedChoice",
    "result",
    "resultImageRef",
    "resultVideoRef",
  ])) return false;
  return typeof story.storyId === "string" && story.storyId.length > 0 &&
    typeof story.title === "string" &&
    typeof story.text === "string" &&
    typeof story.question === "string" &&
    isStringArray(story.choices) &&
    typeof story.createdAt === "string" &&
    isNullableWebMediaReference(story.imageRef) &&
    isNullableWebMediaReference(story.videoRef) &&
    isNullableString(story.selectedChoice) &&
    (story.result === null || isScheduledStoryResultSnapshot(story.result)) &&
    isNullableWebMediaReference(story.resultImageRef) &&
    isNullableWebMediaReference(story.resultVideoRef) &&
    ((story.selectedChoice === null && story.result === null) ||
      (story.selectedChoice !== null && story.result !== null));
}

function isTravelVideoEventSnapshot(value: unknown): boolean {
  if (!isRecord(value) || !hasExactKeys(value, [
    "requestKey",
    "prompt",
    "title",
    "scenario",
    "imageRef",
    "videoRef",
    "completedAtEpochMillis",
  ])) return false;
  return typeof value.requestKey === "string" && value.requestKey.length > 0 &&
    typeof value.prompt === "string" &&
    isNullableString(value.title) &&
    isNullableString(value.scenario) &&
    isNullableWebMediaReference(value.imageRef) &&
    isNullableWebMediaReference(value.videoRef) &&
    isNonNegativeSafeInteger(value.completedAtEpochMillis);
}

function isNullableEpoch(value: unknown): value is number | null {
  return value === null || isNonNegativeSafeInteger(value);
}

function isWebEventsSnapshot(value: unknown): value is WebEventsSnapshot {
  if (!isRecord(value) || !hasExactKeys(value, [
    "stories",
    "travelVideos",
    "badgeCount",
    "latestEventAtEpochMillis",
    "lastViewedAtEpochMillis",
    "initialFocusTravelRequestKey",
  ])) return false;
  return Array.isArray(value.stories) && value.stories.every(isScheduledStoryEventSnapshot) &&
    Array.isArray(value.travelVideos) && value.travelVideos.every(isTravelVideoEventSnapshot) &&
    isNonNegativeSafeInteger(value.badgeCount) &&
    isNullableEpoch(value.latestEventAtEpochMillis) &&
    isNullableEpoch(value.lastViewedAtEpochMillis) &&
    isNullableString(value.initialFocusTravelRequestKey);
}

function isOpenedStoryContentSnapshot(value: unknown): boolean {
  if (!isRecord(value) || !hasExactKeys(value, [
    "storyId",
    "title",
    "text",
    "question",
    "choices",
    "enabledChoice",
    "questionParagraphs",
    "imageRef",
    "videoRef",
  ])) return false;
  return typeof value.storyId === "string" && value.storyId.length > 0 &&
    typeof value.title === "string" &&
    typeof value.text === "string" &&
    typeof value.question === "string" &&
    isStringArray(value.choices) &&
    isNullableString(value.enabledChoice) &&
    isStringArray(value.questionParagraphs) && value.questionParagraphs.length > 0 &&
    isNullableWebMediaReference(value.imageRef) &&
    isNullableWebMediaReference(value.videoRef) &&
    (value.enabledChoice === null || value.choices.includes(value.enabledChoice));
}

function isOpenedStoryResultSnapshot(value: unknown): boolean {
  if (!isRecord(value) || !hasExactKeys(value, [
    "requestKey",
    "answer",
    "text",
    "reaction",
    "consequence",
    "experienceGained",
    "paragraphs",
    "imageRef",
    "videoRef",
  ])) return false;
  return typeof value.requestKey === "string" && value.requestKey.length > 0 &&
    typeof value.answer === "string" &&
    typeof value.text === "string" &&
    typeof value.reaction === "string" &&
    typeof value.consequence === "string" &&
    isNonNegativeSafeInteger(value.experienceGained) &&
    isStringArray(value.paragraphs) && value.paragraphs.length > 0 &&
    isNullableWebMediaReference(value.imageRef) &&
    isNullableWebMediaReference(value.videoRef);
}

function isWebOpenedStorySnapshot(value: unknown): value is WebOpenedStorySnapshot {
  if (!isRecord(value) || !hasExactKeys(value, [
    "phase",
    "kind",
    "origin",
    "story",
    "durableRequestKey",
    "pendingChoice",
    "result",
    "error",
  ])) return false;
  if (
    !["question", "choicePending", "retryable", "result"].includes(String(value.phase)) ||
    !["scheduled", "onboardingBat"].includes(String(value.kind)) ||
    !["dashboard", "events"].includes(String(value.origin)) ||
    !isOpenedStoryContentSnapshot(value.story) ||
    !isNullableString(value.durableRequestKey) ||
    !isNullableString(value.pendingChoice) ||
    !(value.result === null || isOpenedStoryResultSnapshot(value.result)) ||
    !isNullableString(value.error)
  ) return false;
  const story = value.story as { choices: string[] };
  if (value.kind === "onboardingBat" && value.origin !== "dashboard") return false;
  if (value.phase === "choicePending" && value.kind !== "scheduled") return false;
  if (value.phase === "question") {
    return value.pendingChoice === null && value.result === null;
  }
  if (value.phase === "choicePending" || value.phase === "retryable") {
    return typeof value.durableRequestKey === "string" && value.durableRequestKey.length > 0 &&
      typeof value.pendingChoice === "string" && value.pendingChoice.length > 0 &&
      story.choices.includes(value.pendingChoice) && value.result === null &&
      (value.phase !== "choicePending" || value.error === null);
  }
  return value.pendingChoice === null &&
    typeof value.durableRequestKey === "string" && value.durableRequestKey.length > 0 &&
    isRecord(value.result) && value.result.requestKey === value.durableRequestKey;
}

function isDashboardSnapshot(value: unknown): value is DashboardSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, ["reply", "chat", "feed", "outfit", "travel"]) &&
    (value.reply === null || isDashboardReplySnapshot(value.reply)) &&
    isDashboardChatSnapshot(value.chat) &&
    isDashboardFeedSnapshot(value.feed) &&
    isDashboardOutfitSnapshot(value.outfit) &&
    isDashboardTravelSnapshot(value.travel);
}

function isDashboardChatSnapshot(value: unknown): value is DashboardChatSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, [
      "draft",
      "error",
      "activeRequestKey",
      "queuedRequestKey",
      "thinking",
    ]) &&
    typeof value.draft === "string" &&
    isNullableString(value.error) &&
    isNullableString(value.activeRequestKey) &&
    isNullableString(value.queuedRequestKey) &&
    typeof value.thinking === "boolean";
}

function isDashboardFeedSnapshot(value: unknown): value is DashboardFeedSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, [
      "error",
      "activeRequestKey",
      "activeFood",
      "audioIndex",
      "pulseId",
      "thinking",
    ]) &&
    isNullableString(value.error) &&
    isNullableString(value.activeRequestKey) &&
    (value.activeFood === null || isDashboardFood(value.activeFood)) &&
    (value.audioIndex === null || Number.isSafeInteger(value.audioIndex)) &&
    Number.isSafeInteger(value.pulseId) &&
    typeof value.thinking === "boolean";
}

function isDashboardOutfitSnapshot(value: unknown): value is DashboardOutfitSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, [
      "draft",
      "error",
      "activeRequestKey",
      "thinking",
      "experienceCost",
      "pending",
    ]) &&
    typeof value.draft === "string" &&
    isNullableString(value.error) &&
    isNullableString(value.activeRequestKey) &&
    typeof value.thinking === "boolean" &&
    isNonNegativeSafeInteger(value.experienceCost) &&
    (value.pending === null || isDashboardOutfitPendingSnapshot(value.pending));
}

function isDashboardOutfitPendingSnapshot(
  value: unknown,
): value is DashboardOutfitPendingSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, [
      "requestKey",
      "status",
      "prompt",
      "displayItem",
      "experienceCost",
    ]) &&
    typeof value.requestKey === "string" &&
    isPendingOperationStatus(value.status) &&
    typeof value.prompt === "string" &&
    typeof value.displayItem === "string" &&
    isNonNegativeSafeInteger(value.experienceCost);
}

function isDashboardTravelSnapshot(value: unknown): value is DashboardTravelSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, ["draft", "error", "activeRequestKey", "thinking", "pending"]) &&
    typeof value.draft === "string" &&
    isNullableString(value.error) &&
    isNullableString(value.activeRequestKey) &&
    typeof value.thinking === "boolean" &&
    (value.pending === null || isDashboardTravelPendingSnapshot(value.pending));
}

function isDashboardTravelPendingSnapshot(
  value: unknown,
): value is DashboardTravelPendingSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, ["requestKey", "status", "prompt"]) &&
    typeof value.requestKey === "string" &&
    isPendingOperationStatus(value.status) &&
    typeof value.prompt === "string";
}

function isDashboardReplySnapshot(value: unknown): value is DashboardReplySnapshot {
  return isRecord(value) &&
    hasExactKeys(value, [
      "source",
      "requestKey",
      "portions",
      "portionIndex",
      "hasNextPortion",
      "autoAdvanceDelayMillis",
    ]) &&
    ["chat", "feed", "transient", "firstSession", "settled"].includes(String(value.source)) &&
    typeof value.requestKey === "string" &&
    Array.isArray(value.portions) &&
    value.portions.length > 0 &&
    value.portions.every((portion) => typeof portion === "string") &&
    Number.isSafeInteger(value.portionIndex) &&
    Number(value.portionIndex) >= 0 &&
    Number(value.portionIndex) < value.portions.length &&
    typeof value.hasNextPortion === "boolean" &&
    value.hasNextPortion === (Number(value.portionIndex) < value.portions.length - 1) &&
    Number.isSafeInteger(value.autoAdvanceDelayMillis) &&
    Number(value.autoAdvanceDelayMillis) >= 0;
}

function isDashboardFood(value: unknown): value is DashboardFood {
  return value === "berry-bowl" || value === "leaf-crunch";
}

function isFirstSessionSnapshot(value: unknown): value is FirstSessionSnapshot {
  return (
    isRecord(value) &&
    hasExactKeys(value, ["stage", "allowedAction", "messagePortions", "selectedDestination"]) &&
    [
      "awaiting-chat",
      "awaiting-chat-followup",
      "awaiting-first-food",
      "awaiting-remedy",
      "awaiting-travel",
      "confirming-travel",
      "awaiting-completion-message",
      "completed",
    ].includes(String(value.stage)) &&
    (value.allowedAction === null ||
      ["chat", "feed", "travel", "outfit"].includes(String(value.allowedAction))) &&
    Array.isArray(value.messagePortions) &&
    value.messagePortions.every((portion) => typeof portion === "string") &&
    (value.selectedDestination === null || typeof value.selectedDestination === "string")
  );
}

function isPetTapFeedbackSnapshot(value: unknown): value is PetTapFeedbackSnapshot {
  return (
    isRecord(value) &&
    hasExactKeys(value, ["eventId", "rewarded", "thanks", "visibleMillis"]) &&
    typeof value.eventId === "string" &&
    typeof value.rewarded === "boolean" &&
    (value.thanks === null || typeof value.thanks === "string") &&
    typeof value.visibleMillis === "number" &&
    Number.isSafeInteger(value.visibleMillis) &&
    value.visibleMillis >= 0
  );
}

function isPendingOperationsSnapshot(value: unknown): value is PendingOperationsSnapshot {
  return isRecord(value) &&
    hasExactKeys(value, ["chat", "outfit", "travel"]) &&
    [value.chat, value.outfit, value.travel].every(
    (operation) => operation === null || isPendingOperationSnapshot(operation),
  );
}

function isPendingOperationSnapshot(value: unknown): value is PendingOperationSnapshot {
  return (
    isRecord(value) &&
    hasExactKeys(value, ["requestKey", "status", "prompt"]) &&
    typeof value.requestKey === "string" &&
    isPendingOperationStatus(value.status) &&
    (value.prompt === null || typeof value.prompt === "string")
  );
}

function isPendingOperationStatus(value: unknown): value is PendingOperationStatus {
  return [
    "pending",
    "dispatching",
    "attached",
    "ready",
    "retryable",
    "outcomeUnknown",
    "failed",
    "applyConflict",
    "completed",
  ].includes(String(value));
}

function isCreateSnapshot(value: unknown): value is CreateSnapshot {
  return (
    isRecord(value) &&
    hasExactKeys(value, [
      "step",
      "title",
      "options",
      "nextQuestion",
      "phase",
      "generation",
      "error",
      "retryTarget",
    ]) &&
    Number.isSafeInteger(value.step) &&
    typeof value.title === "string" &&
    Array.isArray(value.options) &&
    value.options.every((option) => typeof option === "string") &&
    (value.nextQuestion === null || (
      isRecord(value.nextQuestion) &&
      hasExactKeys(value.nextQuestion, ["title", "options"]) &&
      typeof value.nextQuestion.title === "string" &&
      Array.isArray(value.nextQuestion.options) &&
      value.nextQuestion.options.every((option) => typeof option === "string")
    )) &&
    ["initial", "transition", "formed"].includes(String(value.phase)) &&
    ["idle", "running", "ready", "retryable", "failed"].includes(String(value.generation)) &&
    (value.error === null || typeof value.error === "string") &&
    (value.retryTarget === null ||
      ["persistence", "generation", "finalization"].includes(String(value.retryTarget)))
  );
}

function isPetSnapshot(value: unknown): value is PetSnapshot {
  if (!isRecord(value) || !isRecord(value.media)) return false;
  return (
    hasExactKeys(value, [
      "name",
      "stageLabel",
      "experience",
      "hunger",
      "happiness",
      "energy",
      "message",
      "petTapProgress",
      "media",
    ]) &&
    hasExactKeys(value.media, ["videoRef", "posterRef", "sadVideoRef", "happyVideoRef"]) &&
    [value.name, value.stageLabel, value.message].every(
      (field) => typeof field === "string",
    ) &&
    [value.experience, value.hunger, value.happiness, value.energy, value.petTapProgress].every(
      Number.isSafeInteger,
    ) &&
    [value.media.videoRef, value.media.posterRef, value.media.sadVideoRef, value.media.happyVideoRef]
      .every(isNullableWebMediaReference)
  );
}

export function isSafeAreaSnapshot(value: unknown): value is SafeAreaSnapshot {
  if (!isRecord(value) || !hasExactKeys(value, [
    "top",
    "right",
    "bottom",
    "left",
    "imeTop",
    "imeHeight",
    "imeProgress",
  ])) return false;
  return [
    value.top,
    value.right,
    value.bottom,
    value.left,
    value.imeTop,
    value.imeHeight,
    value.imeProgress,
  ].every((field) => typeof field === "number" && Number.isFinite(field));
}

function isAppRoute(value: unknown): value is AppRoute {
  return [
    "create",
    "dashboard",
    "events",
    "story",
    "connectionError",
    "localDataError",
  ].includes(String(value));
}

function isDashboardMode(value: unknown): value is DashboardMode {
  return ["idle", "chat", "feed", "outfit", "travel"].includes(String(value));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isJsonValue(value: unknown): value is JsonValue {
  if (
    value === null ||
    typeof value === "string" ||
    typeof value === "boolean" ||
    (typeof value === "number" && Number.isFinite(value))
  ) return true;
  if (Array.isArray(value)) return value.every(isJsonValue);
  return isRecord(value) && Object.values(value).every(isJsonValue);
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function asObject(value: JsonValue): Record<string, JsonValue> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value : {};
}

function requiredString(value: JsonValue | undefined): string {
  if (typeof value !== "string" || !value.trim()) throw new Error("INVALID_PAYLOAD");
  return value;
}

function requiredNumber(value: JsonValue | undefined): number {
  if (typeof value !== "number" || !Number.isFinite(value)) throw new Error("INVALID_PAYLOAD");
  return value;
}

function requiredIntegerInRange(
  value: JsonValue | undefined,
  minimum: number,
  maximum: number,
): number {
  if (!Number.isSafeInteger(value) || Number(value) < minimum || Number(value) > maximum) {
    throw new Error("INVALID_PAYLOAD");
  }
  return Number(value);
}
