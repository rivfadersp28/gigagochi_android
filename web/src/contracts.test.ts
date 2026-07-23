import { describe, expect, it } from "vitest";
import {
  BRIDGE_PROTOCOL_VERSION,
  BRIDGE_SCHEMA_HASH,
  isAppSnapshot,
  isBridgeEvent,
  isBridgeResponse,
  isLifecycleChangedEventPayload,
  isPermissionChangedEventPayload,
  isSystemBackEventPayload,
  isTravelShareCompletedEventPayload,
  makeProductCommand,
} from "./contracts";
import { createMockSnapshot } from "./mockBridge";
import {
  localStoryFixture,
  storyScreenFixture,
  travelVideoFixture,
} from "./test/eventStoryFixtures";

describe("bridge contracts", () => {
  it("accepts the complete canonical snapshot", () => {
    expect(isAppSnapshot(createMockSnapshot("dashboard"))).toBe(true);
    expect(isAppSnapshot(createMockSnapshot("create"))).toBe(true);
    expect(BRIDGE_SCHEMA_HASH).toMatch(/^gigagochi-bridge-v3-[0-9a-f]{64}$/);
  });

  it("requires exact bootstrap capabilities and a safe pending deep-link route", () => {
    const snapshot = createMockSnapshot("dashboard");
    expect(isAppSnapshot({
      ...snapshot,
      pendingDeepLinkTarget: "story",
    })).toBe(true);
    expect(isAppSnapshot({
      ...snapshot,
      pendingDeepLinkTarget: "raw://secret",
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      capabilities: { ...snapshot.capabilities, futureCapability: true },
    })).toBe(false);
    const { opaqueMedia: _omitted, ...incompleteCapabilities } = snapshot.capabilities;
    expect(isAppSnapshot({
      ...snapshot,
      capabilities: incompleteCapabilities,
    })).toBe(false);
  });

  it("rejects unknown fields throughout the canonical snapshot", () => {
    const snapshot = createMockSnapshot("dashboard");
    expect(isAppSnapshot({ ...snapshot, ownerId: "must-stay-native" })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      safeArea: { ...snapshot.safeArea, debugInset: 1 },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      pending: { ...snapshot.pending, backendJobId: "must-stay-native" },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      dashboard: snapshot.dashboard && {
        ...snapshot.dashboard,
        travel: { ...snapshot.dashboard.travel, ready: { sourceUrl: "secret" } },
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      pet: snapshot.pet && { ...snapshot.pet, petId: "must-stay-native" },
    })).toBe(false);

    const create = createMockSnapshot("create");
    expect(isAppSnapshot({
      ...create,
      create: create.create && { ...create.create, backendState: "running" },
    })).toBe(false);
    expect(isAppSnapshot({
      ...create,
      create: create.create && {
        ...create.create,
        nextQuestion: create.create.nextQuestion && {
          ...create.create.nextQuestion,
          ownerId: "must-stay-native",
        },
      },
    })).toBe(false);
  });

  it("rejects unknown fields in every nested legacy DTO", () => {
    const snapshot = createMockSnapshot("dashboard");
    const dashboard = snapshot.dashboard!;
    const withDashboard = (next: typeof dashboard) => isAppSnapshot({
      ...snapshot,
      dashboard: next,
    });
    expect(withDashboard({
      ...dashboard,
      reply: {
        source: "chat",
        requestKey: "reply-1",
        portions: ["Привет"],
        portionIndex: 0,
        hasNextPortion: false,
        autoAdvanceDelayMillis: 6_000,
        ownerId: "must-stay-native",
      },
    } as typeof dashboard)).toBe(false);
    expect(withDashboard({
      ...dashboard,
      chat: { ...dashboard.chat, backendJobId: "must-stay-native" },
    } as typeof dashboard)).toBe(false);
    expect(withDashboard({
      ...dashboard,
      feed: { ...dashboard.feed, backendJobId: "must-stay-native" },
    } as typeof dashboard)).toBe(false);
    expect(withDashboard({
      ...dashboard,
      outfit: { ...dashboard.outfit, backendJobId: "must-stay-native" },
    } as typeof dashboard)).toBe(false);
    expect(withDashboard({
      ...dashboard,
      outfit: {
        ...dashboard.outfit,
        pending: {
          requestKey: "outfit-1",
          status: "pending",
          prompt: "Шарф",
          displayItem: "красный шарф",
          experienceCost: 200,
          backendJobId: "must-stay-native",
        },
      },
    } as typeof dashboard)).toBe(false);
    expect(withDashboard({
      ...dashboard,
      travel: {
        ...dashboard.travel,
        pending: {
          requestKey: "travel-1",
          status: "pending",
          prompt: "Море",
          backendJobId: "must-stay-native",
        },
      },
    } as typeof dashboard)).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      pending: {
        ...snapshot.pending,
        chat: {
          requestKey: "chat-1",
          status: "pending",
          prompt: "Привет",
          backendJobId: "must-stay-native",
        },
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      firstSession: {
        stage: "awaiting-chat",
        allowedAction: "chat",
        messagePortions: ["Привет"],
        selectedDestination: null,
        ownerId: "must-stay-native",
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      petTapFeedback: {
        eventId: "tap-1",
        rewarded: false,
        thanks: null,
        visibleMillis: 5_000,
        ownerId: "must-stay-native",
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      pet: snapshot.pet && {
        ...snapshot.pet,
        media: { ...snapshot.pet.media, sourceUrl: "https://example.test/pet.mp4" },
      },
    })).toBe(false);
  });

  it("accepts only app-scoped Pet media references", () => {
    const snapshot = createMockSnapshot("dashboard");
    expect(isAppSnapshot({
      ...snapshot,
      pet: snapshot.pet && {
        ...snapshot.pet,
        media: {
          ...snapshot.pet.media,
          videoRef: "/media/v1/0123456789abcdef0123456789abcdef/fedcba9876543210fedcba9876543210",
        },
      },
    })).toBe(true);
    for (const videoRef of [
      "https://example.test/pet.mp4",
      "/assets/../secret.mp4",
      "/media/v1/not-a-token/not-a-token",
      "/private/pet.mp4",
    ]) {
      expect(isAppSnapshot({
        ...snapshot,
        pet: snapshot.pet && {
          ...snapshot.pet,
          media: { ...snapshot.pet.media, videoRef },
        },
      })).toBe(false);
    }
  });

  it("separates snapshot routes from native navigation targets", () => {
    const snapshot = createMockSnapshot("dashboard");
    expect(isAppSnapshot({ ...snapshot, route: "travel" })).toBe(false);
    expect(makeProductCommand("NAVIGATE", { route: "events" }, "revision-1"))
      .toMatchObject({ payload: { route: "events" } });
    expect(makeProductCommand("NAVIGATE", { route: "travel" }, "revision-1"))
      .toMatchObject({ payload: { route: "travel" } });
    expect(() => makeProductCommand("NAVIGATE", { route: "dashboard" }, "revision-1"))
      .toThrow("INVALID_PAYLOAD");
    expect(() => makeProductCommand("NAVIGATE", { route: "story" }, "revision-1"))
      .toThrow("INVALID_PAYLOAD");
  });

  it("validates the exact Kotlin response envelope", () => {
    const common = {
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: "document",
      bridgeSessionId: "session",
      requestId: "request",
    } as const;
    const success = { ...common, ok: true, result: {}, error: null };
    const failure = {
      ...common,
      ok: false,
      result: null,
      error: { code: "STATE_CONFLICT", retryable: true },
    };
    expect(isBridgeResponse(success)).toBe(true);
    expect(isBridgeResponse(failure)).toBe(true);
    expect(isBridgeResponse({
      ...failure,
      result: createMockSnapshot("dashboard"),
    })).toBe(true);
    expect(isBridgeResponse({
      ...failure,
      result: createMockSnapshot("dashboard"),
      error: { code: "INTERNAL", retryable: false },
    })).toBe(false);
    expect(isBridgeResponse({ ...success, error: undefined })).toBe(false);
    expect(isBridgeResponse({ ...success, ownerId: "must-stay-native" })).toBe(false);
    expect(isBridgeResponse({ ...failure, result: {} })).toBe(false);
    expect(isBridgeResponse({ ...failure, error: { ...failure.error, detail: "leak" } }))
      .toBe(false);
  });

  it("accepts only exact native event envelopes and supported types", () => {
    const event = {
      kind: "event",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: "document",
      bridgeSessionId: "session",
      subscriptionId: "app-state",
      sequence: 1,
      type: "stateChanged",
      payload: {},
    } as const;
    expect(isBridgeEvent(event)).toBe(true);
    expect(isBridgeEvent({ ...event, type: "travelShareCompleted" })).toBe(true);
    expect(isBridgeEvent({ ...event, type: "navigationRequested" })).toBe(false);
    expect(isBridgeEvent({ ...event, type: "futureNativeEvent" })).toBe(false);
    expect(isBridgeEvent({ ...event, subscriptionId: "other" })).toBe(false);
    expect(isBridgeEvent({ ...event, ownerId: "must-stay-native" })).toBe(false);
  });

  it("rejects partial and malformed snapshots", () => {
    expect(
      isAppSnapshot({
        protocolVersion: BRIDGE_PROTOCOL_VERSION,
        revision: "partial",
        route: "dashboard",
      }),
    ).toBe(false);
    expect(
      isAppSnapshot({
        ...createMockSnapshot("create"),
        create: {
          ...createMockSnapshot("create").create,
          nextQuestion: { title: "Следующий вопрос", options: ["Да", 2] },
        },
      }),
    ).toBe(false);
    expect(
      isAppSnapshot({
        ...createMockSnapshot("dashboard"),
        dashboard: {
          ...createMockSnapshot("dashboard").dashboard,
          outfit: {
            ...createMockSnapshot("dashboard").dashboard?.outfit,
            experienceCost: -1,
          },
        },
      }),
    ).toBe(false);
    expect(
      isAppSnapshot({
        ...createMockSnapshot("dashboard"),
        dashboard: {
          ...createMockSnapshot("dashboard").dashboard,
          feed: {
            ...createMockSnapshot("dashboard").dashboard?.feed,
            pulseId: "1",
          },
        },
      }),
    ).toBe(false);
    expect(
      isAppSnapshot({
        ...createMockSnapshot("dashboard"),
        pet: { ...createMockSnapshot("dashboard").pet, hunger: "100" },
      }),
    ).toBe(false);
  });

  it("strictly validates Events DTO privacy fields and media capabilities", () => {
    const snapshot = createMockSnapshot("events");
    const events = {
      stories: [localStoryFixture()],
      travelVideos: [travelVideoFixture("travel-1")],
      badgeCount: 2,
      latestEventAtEpochMillis: 200,
      lastViewedAtEpochMillis: 100,
      initialFocusTravelRequestKey: "travel-1",
    };
    expect(isAppSnapshot({ ...snapshot, events })).toBe(true);
    expect(isAppSnapshot({
      ...snapshot,
      events: {
        ...events,
        travelVideos: [{ ...events.travelVideos[0], ownerId: "must-stay-native" }],
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      events: {
        ...events,
        stories: [{
          story: { ...events.stories[0].story, petId: "must-stay-native" },
        }],
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      events: {
        ...events,
        travelVideos: [{ ...events.travelVideos[0], videoRef: "https://example.test/leak.mp4" }],
      },
    })).toBe(false);
  });

  it("strictly validates opened Story phase invariants", () => {
    const snapshot = createMockSnapshot("story");
    const question = storyScreenFixture();
    expect(isAppSnapshot({ ...snapshot, story: question })).toBe(true);
    expect(isAppSnapshot({
      ...snapshot,
      story: {
        ...question,
        phase: "choicePending",
        durableRequestKey: "choice-1",
        pendingChoice: "Подойти",
      },
    })).toBe(true);
    expect(isAppSnapshot({
      ...snapshot,
      story: {
        ...question,
        phase: "choicePending",
        durableRequestKey: "choice-1",
        pendingChoice: "Подойти",
        error: "Нельзя показывать ошибку во время выполнения",
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      story: {
        ...question,
        phase: "retryable",
        durableRequestKey: "choice-1",
        pendingChoice: "Подойти",
        error: "Повтори",
      },
    })).toBe(true);
    expect(isAppSnapshot({
      ...snapshot,
      story: {
        ...question,
        phase: "retryable",
        durableRequestKey: "choice-1",
        pendingChoice: "Чужой вариант",
      },
    })).toBe(false);
    expect(isAppSnapshot({
      ...snapshot,
      story: {
        ...question,
        story: { ...question.story, imageRef: "/assets/../secret.png" },
      },
    })).toBe(false);
  });

  it("creates an unpredictable fenced product command", () => {
    const command = makeProductCommand("PET_TAP", {}, "revision-4");
    expect(command.type).toBe("PET_TAP");
    expect(command.expectedSnapshotRevision).toBe("revision-4");
    expect(command.requestKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
  });

  it("requires the exact Create source step", () => {
    expect(makeProductCommand(
      "CREATE_ANSWER",
      { answer: "Тото", step: 2 },
      "revision-4",
    )).toMatchObject({ payload: { answer: "Тото", step: 2 } });
    expect(() => makeProductCommand(
      "CREATE_ANSWER",
      { answer: "Тото", step: 5 },
      "revision-4",
    )).toThrow("INVALID_PAYLOAD");
    expect(() => makeProductCommand(
      "CREATE_ANSWER",
      { answer: "Тото" },
      "revision-4",
    )).toThrow("INVALID_PAYLOAD");
  });

  it("rejects an unknown feed item before it reaches native", () => {
    expect(() => makeProductCommand("FEED_CONSUME", { food: "cake" }, "revision-1"))
      .toThrow("INVALID_PAYLOAD");
  });

  it("validates semantic dashboard and reply commands", () => {
    expect(makeProductCommand("DASHBOARD_OPEN_MODE", { mode: "chat" }, "revision-1"))
      .toMatchObject({ type: "DASHBOARD_OPEN_MODE", payload: { mode: "chat" } });
    expect(makeProductCommand("REPLY_ADVANCE", { requestKey: "chat-1" }, "revision-2"))
      .toMatchObject({ type: "REPLY_ADVANCE", payload: { requestKey: "chat-1" } });
    expect(() => makeProductCommand("DASHBOARD_OPEN_MODE", { mode: "idle" }, "revision-3"))
      .toThrow("INVALID_PAYLOAD");
  });

  it("validates exact typed dashboard draft payloads including empty and max length values", () => {
    expect(makeProductCommand(
      "DASHBOARD_UPDATE_DRAFT",
      { mode: "chat", value: "" },
      "revision-1",
    )).toMatchObject({
      type: "DASHBOARD_UPDATE_DRAFT",
      payload: { mode: "chat", value: "" },
    });
    expect(makeProductCommand(
      "DASHBOARD_UPDATE_DRAFT",
      { mode: "travel", value: "x".repeat(1_000) },
      "revision-2",
    )).toMatchObject({ payload: { mode: "travel", value: "x".repeat(1_000) } });
    expect(() => makeProductCommand(
      "DASHBOARD_UPDATE_DRAFT",
      { mode: "feed", value: "draft" },
      "revision-3",
    )).toThrow("INVALID_PAYLOAD");
    expect(() => makeProductCommand(
      "DASHBOARD_UPDATE_DRAFT",
      { mode: "outfit", value: "x".repeat(1_001) },
      "revision-4",
    )).toThrow("INVALID_PAYLOAD");
    expect(() => makeProductCommand(
      "DASHBOARD_UPDATE_DRAFT",
      { mode: "chat", value: "draft", extra: true },
      "revision-5",
    )).toThrow("INVALID_PAYLOAD");
  });

  it("validates STORY_OPEN before it reaches native", () => {
    expect(makeProductCommand("STORY_OPEN", { storyId: "story-1" }, "revision-1"))
      .toMatchObject({ type: "STORY_OPEN", payload: { storyId: "story-1" } });
    expect(() => makeProductCommand("STORY_OPEN", { storyId: "" }, "revision-1"))
      .toThrow("INVALID_PAYLOAD");
  });

  it("creates the empty transition-completion command", () => {
    expect(makeProductCommand("CREATE_BACKGROUND_COMPLETE", {}, "revision-5"))
      .toMatchObject({
        type: "CREATE_BACKGROUND_COMPLETE",
        payload: {},
        expectedSnapshotRevision: "revision-5",
      });
  });

  it("validates CHAT_RETRY as an exact empty-payload command", () => {
    expect(makeProductCommand("CHAT_RETRY", {}, "revision-6"))
      .toMatchObject({
        type: "CHAT_RETRY",
        payload: {},
        expectedSnapshotRevision: "revision-6",
      });
    expect(() => makeProductCommand(
      "CHAT_RETRY",
      { message: "Не создавай новый запрос" },
      "revision-6",
    )).toThrow("INVALID_PAYLOAD");
  });

  it("validates a typed non-negative system Back sequence", () => {
    expect(isSystemBackEventPayload({ navigationSequence: 3 })).toBe(true);
    expect(isSystemBackEventPayload({ navigationSequence: -1 })).toBe(false);
    expect(isSystemBackEventPayload({ navigationSequence: 1.5 })).toBe(false);
    expect(isSystemBackEventPayload({ navigationSequence: "3" })).toBe(false);
  });

  it("validates typed permission and lifecycle events", () => {
    expect(isPermissionChangedEventPayload({ status: "granted" })).toBe(true);
    expect(isPermissionChangedEventPayload({ status: "prompting" })).toBe(false);
    expect(isLifecycleChangedEventPayload({ state: "background" })).toBe(true);
    expect(isLifecycleChangedEventPayload({ state: "paused" })).toBe(false);
  });

  it("validates exact travel share completion payloads", () => {
    expect(isTravelShareCompletedEventPayload({
      requestKey: "travel-1",
      status: "opened",
    })).toBe(true);
    expect(isTravelShareCompletedEventPayload({
      requestKey: "travel-1",
      status: "failed",
    })).toBe(true);
    expect(isTravelShareCompletedEventPayload({
      requestKey: "travel-1",
      status: "accepted",
    })).toBe(false);
    expect(isTravelShareCompletedEventPayload({
      requestKey: "travel-1",
      status: "opened",
      ownerId: "must-stay-native",
    })).toBe(false);
    expect(isTravelShareCompletedEventPayload({ requestKey: "", status: "opened" }))
      .toBe(false);
  });
});
