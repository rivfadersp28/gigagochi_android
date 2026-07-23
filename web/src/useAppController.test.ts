import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { BridgeError, type GigagochiBridge } from "./bridge";
import type { AppSnapshot, JsonValue, ProductCommand } from "./contracts";
import { createMockSnapshot } from "./mockBridge";
import { storyOutcomeFixture, storyScreenFixture } from "./test/eventStoryFixtures";
import {
  isOlderSnapshotRevision,
  patchSnapshotInsets,
  patchSnapshotNotificationPermission,
  useAppController,
} from "./useAppController";

function productionSnapshot(
  sequence: number,
  happiness = 82,
  runtimeId = "runtime-a",
): AppSnapshot {
  const snapshot = createMockSnapshot("dashboard");
  return {
    ...snapshot,
    revision: `r1:${runtimeId}:${sequence}:sha256:test-${sequence}`,
    pet: snapshot.pet ? { ...snapshot.pet, happiness } : null,
  };
}

function productionStorySnapshot(
  sequence: number,
  story = storyScreenFixture(),
): AppSnapshot {
  return {
    ...createMockSnapshot("story"),
    revision: `r1:runtime-story:${sequence}:sha256:story-${sequence}`,
    story,
  };
}

function testBridge(overrides: Partial<GigagochiBridge>): GigagochiBridge {
  return {
    bootstrap: async () => productionSnapshot(1),
    dispatch: async () => productionSnapshot(2),
    call: async () => ({} as JsonValue),
    ...overrides,
  };
}

describe("continuous native inset patches", () => {
  it("updates only safe-area data and preserves the snapshot revision", () => {
    const snapshot = createMockSnapshot("dashboard");
    const next = patchSnapshotInsets(snapshot, {
      top: 31,
      right: 2,
      bottom: 18,
      left: 2,
      imeTop: 540,
      imeHeight: 334,
      imeProgress: 0.625,
    });

    expect(next).not.toBe(snapshot);
    expect(next?.revision).toBe(snapshot.revision);
    expect(next?.safeArea).toMatchObject({ top: 31, imeHeight: 334, imeProgress: 0.625 });
    expect(next?.pet).toBe(snapshot.pet);
  });

  it("ignores incomplete or non-finite inset payloads", () => {
    const snapshot = createMockSnapshot("dashboard");
    expect(patchSnapshotInsets(snapshot, { top: 1 })).toBe(snapshot);
    expect(patchSnapshotInsets(snapshot, {
      ...snapshot.safeArea,
      imeProgress: Number.NaN,
    })).toBe(snapshot);
  });
});

describe("platform bridge state", () => {
  it("requests notification permission once on a product route", async () => {
    const call = vi.fn().mockResolvedValue({} as JsonValue);
    const bridge = testBridge({ call });
    const { rerender } = renderHook(() => useAppController(bridge));

    await waitFor(() => expect(
      call.mock.calls.filter(([method]) => method === "requestNotificationPermission"),
    ).toHaveLength(1));
    rerender();

    expect(call.mock.calls.filter(([method]) => method === "requestNotificationPermission"))
      .toHaveLength(1);
  });

  it("does not request notification permission on an error route", async () => {
    const call = vi.fn().mockResolvedValue({} as JsonValue);
    const errorSnapshot = createMockSnapshot("connectionError");
    const bridge = testBridge({
      bootstrap: vi.fn(async () => errorSnapshot),
      call,
    });
    const { result } = renderHook(() => useAppController(bridge));

    await waitFor(() => expect(result.current.snapshot?.route).toBe("connectionError"));
    expect(call.mock.calls.some(([method]) => method === "requestNotificationPermission"))
      .toBe(false);
  });

  it("applies ordered permission and lifecycle events", async () => {
    const bridge = testBridge({});
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: { type: "permissionChanged", payload: { status: "denied" } },
      }));
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: { type: "lifecycleChanged", payload: { state: "background" } },
      }));
    });

    expect(result.current.snapshot?.notificationPermission).toBe("denied");
    expect(result.current.lifecycleState).toBe("background");

    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: { type: "lifecycleChanged", payload: { state: "foreground" } },
      }));
    });
    expect(result.current.lifecycleState).toBe("foreground");
  });

  it("patches permission without changing product revision", () => {
    const snapshot = createMockSnapshot("dashboard");
    const next = patchSnapshotNotificationPermission(snapshot, "granted");

    expect(next?.revision).toBe(snapshot.revision);
    expect(next?.notificationPermission).toBe("granted");
    expect(next?.pet).toBe(snapshot.pet);
  });
});

describe("monotonic native snapshots", () => {
  it("orders production revisions per runtime and supports debug revision fallback", () => {
    expect(isOlderSnapshotRevision(
      "r1:runtime-a:8:sha256:old",
      "r1:runtime-a:9:sha256:new",
    )).toBe(true);
    expect(isOlderSnapshotRevision(
      "r1:runtime-b:1:sha256:restart",
      "r1:runtime-a:99:sha256:old-runtime",
    )).toBe(false);
    expect(isOlderSnapshotRevision("debug-3", "debug-4")).toBe(true);
    expect(isOlderSnapshotRevision("mock-1", "mock-2")).toBe(false);
  });

  it("does not let a late dispatch response roll back a newer native event", async () => {
    const initial = productionSnapshot(1, 81);
    const response = productionSnapshot(2, 82);
    const eventSnapshot = productionSnapshot(3, 99);
    let resolveDispatch!: (snapshot: AppSnapshot) => void;
    const dispatch = vi.fn((_command: ProductCommand) => new Promise<AppSnapshot>((resolve) => {
      resolveDispatch = resolve;
    }));
    const bridge = testBridge({
      bootstrap: vi.fn(async () => initial),
      dispatch,
    });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

    let operation!: Promise<void>;
    act(() => {
      operation = result.current.dispatch("PET_TAP");
    });
    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(1));
    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: { type: "stateChanged", payload: eventSnapshot },
      }));
    });
    expect(result.current.snapshot?.revision).toBe(eventSnapshot.revision);

    await act(async () => {
      resolveDispatch(response);
      await operation;
    });

    expect(result.current.snapshot?.revision).toBe(eventSnapshot.revision);
    expect(result.current.snapshot?.pet?.happiness).toBe(99);
  });
});

describe("safe product command retry", () => {
  it.each(["STATE_CONFLICT", "BRIDGE_TIMEOUT"] as const)(
    "reconciles %s once with the same key and payload at the new revision",
    async (errorCode) => {
      const initial = productionSnapshot(1);
      const recovered = productionSnapshot(2);
      const applied = productionSnapshot(3, 97);
      const bootstrap = vi.fn()
        .mockResolvedValueOnce(initial)
        .mockResolvedValueOnce(recovered);
      const dispatch = vi.fn()
        .mockRejectedValueOnce(new BridgeError(errorCode, true))
        .mockResolvedValueOnce(applied);
      const bridge = testBridge({ bootstrap, dispatch });
      const { result } = renderHook(() => useAppController(bridge));
      await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

      await act(async () => {
        await result.current.dispatch("PET_TAP");
      });

      expect(bootstrap).toHaveBeenCalledTimes(2);
      expect(dispatch).toHaveBeenCalledTimes(2);
      const first = dispatch.mock.calls[0][0] as ProductCommand;
      const retry = dispatch.mock.calls[1][0] as ProductCommand;
      expect(retry.requestKey).toBe(first.requestKey);
      expect(retry.payload).toBe(first.payload);
      expect(first.expectedSnapshotRevision).toBe(initial.revision);
      expect(retry.expectedSnapshotRevision).toBe(recovered.revision);
      expect(result.current.snapshot?.revision).toBe(applied.revision);
    },
  );

  it("uses the canonical conflict snapshot without a redundant bootstrap round trip", async () => {
    const initial = productionSnapshot(1);
    const recovered = productionSnapshot(2);
    const applied = productionSnapshot(3, 97);
    const bootstrap = vi.fn().mockResolvedValue(initial);
    const dispatch = vi.fn()
      .mockRejectedValueOnce(new BridgeError("STATE_CONFLICT", true, recovered))
      .mockResolvedValueOnce(applied);
    const bridge = testBridge({ bootstrap, dispatch });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

    await act(async () => {
      await result.current.dispatch("PET_TAP");
    });

    expect(bootstrap).toHaveBeenCalledTimes(1);
    expect(dispatch).toHaveBeenCalledTimes(2);
    const first = dispatch.mock.calls[0][0] as ProductCommand;
    const retry = dispatch.mock.calls[1][0] as ProductCommand;
    expect(retry.requestKey).toBe(first.requestKey);
    expect(retry.expectedSnapshotRevision).toBe(recovered.revision);
    expect(result.current.snapshot?.revision).toBe(applied.revision);
  });

  it("rejects a terminal command failure without exposing a raw global error", async () => {
    const failure = new BridgeError("WRONG_STAGE", false);
    const dispatch = vi.fn().mockRejectedValue(failure);
    const bridge = testBridge({ dispatch });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    await act(async () => {
      await expect(result.current.dispatch("PET_TAP")).rejects.toBe(failure);
    });

    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(result.current.error).toBeNull();
  });
});

describe("Events and Story bridge helpers", () => {
  it("holds local ChoicePending through question snapshots until an authoritative result", async () => {
    const initial = productionStorySnapshot(1);
    let resolveDispatch!: (snapshot: AppSnapshot) => void;
    const dispatch = vi.fn((_command: ProductCommand) => new Promise<AppSnapshot>((resolve) => {
      resolveDispatch = resolve;
    }));
    const bridge = testBridge({ bootstrap: vi.fn(async () => initial), dispatch });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

    let operation!: Promise<void>;
    act(() => {
      operation = result.current.chooseStory(initial.story!.story.storyId, "Подойти");
    });
    expect(result.current.storyChoicePending).toEqual({
      storyId: initial.story!.story.storyId,
      choice: "Подойти",
    });
    await waitFor(() => expect(dispatch).toHaveBeenCalledOnce());

    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: { type: "stateChanged", payload: productionStorySnapshot(2) },
      }));
    });
    expect(result.current.storyChoicePending).not.toBeNull();

    const outcome = storyOutcomeFixture();
    const resolved = productionStorySnapshot(3, storyScreenFixture({
      phase: "result",
      durableRequestKey: outcome.requestKey,
      result: outcome,
    }));
    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: { type: "stateChanged", payload: resolved },
      }));
    });
    expect(result.current.storyChoicePending).toBeNull();

    await act(async () => {
      resolveDispatch(resolved);
      await operation;
    });
  });

  it("clears local ChoicePending after a terminal bridge failure", async () => {
    const initial = productionStorySnapshot(1);
    const failure = new BridgeError("WRONG_STAGE", false);
    const bridge = testBridge({
      bootstrap: vi.fn(async () => initial),
      dispatch: vi.fn().mockRejectedValue(failure),
    });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

    let operation!: Promise<void>;
    act(() => {
      operation = result.current.chooseStory(initial.story!.story.storyId, "Подойти");
    });
    expect(result.current.storyChoicePending).not.toBeNull();
    await act(async () => {
      await expect(operation).rejects.toBe(failure);
    });
    expect(result.current.storyChoicePending).toBeNull();
  });

  it("clears local ChoicePending when the authoritative response is retryable", async () => {
    const initial = productionStorySnapshot(1);
    const retryable = productionStorySnapshot(2, storyScreenFixture({
      phase: "retryable",
      durableRequestKey: "choice-retryable",
      pendingChoice: "Подойти",
      error: "Попробуй снова",
    }));
    const bridge = testBridge({
      bootstrap: vi.fn(async () => initial),
      dispatch: vi.fn(async () => retryable),
    });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

    let operation!: Promise<void>;
    act(() => {
      operation = result.current.chooseStory(initial.story!.story.storyId, "Подойти");
    });
    expect(result.current.storyChoicePending).not.toBeNull();
    await act(async () => operation);

    expect(result.current.snapshot?.story?.phase).toBe("retryable");
    expect(result.current.storyChoicePending).toBeNull();
  });

  it("hands local pending state off to authoritative choicePending without a stale lock", async () => {
    const initial = productionStorySnapshot(1);
    const pending = productionStorySnapshot(2, storyScreenFixture({
      phase: "choicePending",
      durableRequestKey: "choice-pending",
      pendingChoice: "Подойти",
    }));
    const bridge = testBridge({
      bootstrap: vi.fn(async () => initial),
      dispatch: vi.fn(async () => pending),
    });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot?.revision).toBe(initial.revision));

    await act(async () => {
      await result.current.chooseStory(initial.story!.story.storyId, "Подойти");
    });

    expect(result.current.snapshot?.story?.phase).toBe("choicePending");
    expect(result.current.storyChoicePending).toBeNull();
  });

  it("marks each exact latest Events timestamp at most once after success", async () => {
    const dispatch = vi.fn(async (_command: ProductCommand) => productionSnapshot(2));
    const bridge = testBridge({ dispatch });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    act(() => {
      result.current.markEventsViewed(100);
      result.current.markEventsViewed(100);
    });
    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(1));
    expect(dispatch.mock.calls[0][0]).toMatchObject({
      type: "EVENTS_MARK_VIEWED",
      payload: { viewedAt: 100 },
    });

    act(() => {
      result.current.markEventsViewed(100);
      result.current.markEventsViewed(101);
    });
    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(2));
    expect(dispatch.mock.calls[1][0]).toMatchObject({ payload: { viewedAt: 101 } });
  });

  it("allows a viewed timestamp to be retried after terminal failure", async () => {
    const dispatch = vi.fn((_command: ProductCommand) => Promise.resolve(productionSnapshot(2)))
      .mockRejectedValueOnce(new BridgeError("WRONG_STAGE", false))
      .mockResolvedValueOnce(productionSnapshot(2));
    const bridge = testBridge({ dispatch });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    act(() => result.current.markEventsViewed(200));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.markEventsViewed(200));

    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(2));
  });

  it("keeps sharing pending after acceptance and settles only the matching completion", async () => {
    const call = vi.fn(async (method: string): Promise<JsonValue> => (
      method === "shareTravelVideo" ? "accepted" : {}
    ));
    const bridge = testBridge({ call });
    const { result } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    const first = result.current.shareTravelVideo("travel-1");
    const duplicate = result.current.shareTravelVideo("travel-1");
    let settled = false;
    void first.finally(() => { settled = true; });
    await waitFor(() => expect(
      call.mock.calls.filter(([method]) => method === "shareTravelVideo"),
    ).toHaveLength(1));
    await Promise.resolve();
    expect(settled).toBe(false);
    expect(call).toHaveBeenCalledWith("shareTravelVideo", { requestKey: "travel-1" });

    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: {
          type: "travelShareCompleted",
          payload: { requestKey: "other-travel", status: "opened" },
        },
      }));
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: {
          type: "travelShareCompleted",
          payload: { requestKey: "travel-1", status: "opened", extra: true },
        },
      }));
    });
    expect(settled).toBe(false);

    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: {
          type: "travelShareCompleted",
          payload: { requestKey: "travel-1", status: "opened" },
        },
      }));
    });
    await expect(Promise.all([first, duplicate])).resolves.toEqual(["opened", "opened"]);
  });

  it("rejects failed completion, invalid acceptance, bridge failure, and unmount", async () => {
    const call = vi.fn(async (method: string): Promise<JsonValue> => (
      method === "shareTravelVideo" ? "accepted" : {}
    ));
    const bridge = testBridge({ call });
    const { result, unmount } = renderHook(() => useAppController(bridge));
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    const failed = result.current.shareTravelVideo("travel-failed");
    act(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: {
          type: "travelShareCompleted",
          payload: { requestKey: "travel-failed", status: "failed" },
        },
      }));
    });
    await expect(failed).rejects.toThrow("TRAVEL_SHARE_FAILED");

    call.mockResolvedValueOnce({ accepted: true });
    await expect(result.current.shareTravelVideo("travel-invalid-result")).rejects.toThrow(
      "INVALID_SHARE_RESULT",
    );

    call.mockRejectedValueOnce(new BridgeError("INVALID_PAYLOAD", false));
    await expect(result.current.shareTravelVideo("travel-rejected")).rejects.toThrow(
      "INVALID_PAYLOAD",
    );

    const cancelled = result.current.shareTravelVideo("travel-unmounted");
    const cancelledAssertion = expect(cancelled).rejects.toThrow("TRAVEL_SHARE_CANCELLED");
    unmount();
    await cancelledAssertion;
  });
});
