import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App, StartupError, StartupLoading, startupErrorMessage } from "./App";
import {
  BRIDGE_PROTOCOL_VERSION,
  type AppSnapshot,
  type BridgeEvent,
  type BridgeRequest,
  type BridgeResponse,
  type NavigationReadyPayload,
  type ProductCommand,
} from "./contracts";
import { createMockSnapshot } from "./mockBridge";
import {
  localStoryFixture,
  storyOutcomeFixture,
  storyScreenFixture,
  travelVideoFixture,
} from "./test/eventStoryFixtures";

class InteractiveNativePort {
  onmessage: ((event: { data: string }) => void) | null = null;
  readonly posted: BridgeRequest[] = [];
  private readonly sessionId = crypto.randomUUID();
  private snapshot: AppSnapshot;
  private revision = 0;
  private eventSequence = 0;
  private documentId: string | null = null;
  private holdTravelShareCompletion = false;
  private readonly heldTravelShareKeys: string[] = [];
  private readonly heldDrafts: Array<{
    request: BridgeRequest;
    command: Extract<ProductCommand, { type: "DASHBOARD_UPDATE_DRAFT" }>;
  }> = [];

  constructor(
    snapshot: AppSnapshot,
    private readonly failDispatchType: ProductCommand["type"] | null = null,
    private readonly holdDraftResponses = false,
  ) {
    this.snapshot = snapshot;
  }

  postMessage(raw: string): void {
    const request = JSON.parse(raw) as BridgeRequest;
    this.posted.push(request);
    this.documentId ??= request.documentId;
    let result: unknown = {};
    let completedTravelShareKey: string | null = null;
    if (request.method === "bootstrap") {
      result = this.snapshot;
    } else if (request.method === "shareTravelVideo") {
      const payload = request.payload as { requestKey?: unknown };
      if (typeof payload.requestKey !== "string") throw new Error("INVALID_SHARE_PAYLOAD");
      completedTravelShareKey = payload.requestKey;
      result = "accepted";
    } else if (request.method === "dispatch") {
      const command = request.payload as unknown as ProductCommand;
      if (command.type === this.failDispatchType) {
        const failure: BridgeResponse = {
          kind: "response",
          protocolVersion: BRIDGE_PROTOCOL_VERSION,
          documentId: request.documentId,
          bridgeSessionId: this.sessionId,
          requestId: request.requestId,
          ok: false,
          result: null,
          error: { code: "WRONG_STAGE", retryable: false },
        };
        this.onmessage?.({ data: JSON.stringify(failure) });
        return;
      }
      if (command.type === "DASHBOARD_UPDATE_DRAFT" && this.holdDraftResponses) {
        this.heldDrafts.push({ request, command });
        return;
      }
      if (command.type === "DASHBOARD_OPEN_MODE") {
        this.snapshot = {
          ...this.snapshot,
          revision: `native-${++this.revision}`,
          dashboardMode: command.payload.mode,
        };
      } else if (command.type === "DASHBOARD_CLOSE_MODE") {
        this.snapshot = {
          ...this.snapshot,
          revision: `native-${++this.revision}`,
          dashboardMode: "idle",
        };
      } else if (command.type === "DASHBOARD_UPDATE_DRAFT") {
        this.applyDraft(command);
      }
      result = this.snapshot;
    }
    const response: BridgeResponse = {
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: this.sessionId,
      requestId: request.requestId,
      ok: true,
      result: result as never,
      error: null,
    };
    this.onmessage?.({ data: JSON.stringify(response) });
    if (completedTravelShareKey) {
      if (this.holdTravelShareCompletion) {
        this.heldTravelShareKeys.push(completedTravelShareKey);
      } else {
        queueMicrotask(() => this.emitTravelShareCompletion(completedTravelShareKey));
      }
    }
  }

  holdTravelShareCompletions(): void {
    this.holdTravelShareCompletion = true;
  }

  completeNextTravelShare(status: "opened" | "failed" = "opened"): void {
    const requestKey = this.heldTravelShareKeys.shift();
    if (!requestKey) throw new Error("NO_HELD_TRAVEL_SHARE");
    this.emitTravelShareCompletion(requestKey, status);
  }

  releaseNextDraft(): void {
    const held = this.heldDrafts.shift();
    if (!held) throw new Error("NO_HELD_DRAFT");
    this.applyDraft(held.command);
    const response: BridgeResponse = {
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: held.request.documentId,
      bridgeSessionId: this.sessionId,
      requestId: held.request.requestId,
      ok: true,
      result: this.snapshot as never,
      error: null,
    };
    this.onmessage?.({ data: JSON.stringify(response) });
  }

  private applyDraft(
    command: Extract<ProductCommand, { type: "DASHBOARD_UPDATE_DRAFT" }>,
  ): void {
    const dashboard = this.snapshot.dashboard;
    if (!dashboard) throw new Error("DASHBOARD_MISSING");
    const mode = command.payload.mode;
    this.snapshot = {
      ...this.snapshot,
      revision: `native-${++this.revision}`,
      dashboard: {
        ...dashboard,
        [mode]: {
          ...dashboard[mode],
          draft: command.payload.value,
          error: null,
        },
      },
    };
  }

  latestNavigationReady(): NavigationReadyPayload | null {
    for (let index = this.posted.length - 1; index >= 0; index -= 1) {
      const request = this.posted[index];
      if (request.method === "navigationReady") {
        return request.payload as unknown as NavigationReadyPayload;
      }
    }
    return null;
  }

  emitSystemBack(navigationSequence: number): void {
    this.emitEvent("systemBack", { navigationSequence });
  }

  emitStateChanged(snapshot: AppSnapshot): void {
    this.snapshot = snapshot;
    this.emitEvent("stateChanged", snapshot as unknown as BridgeEvent["payload"]);
  }

  emitLifecycle(state: "foreground" | "background"): void {
    this.emitEvent("lifecycleChanged", { state });
  }

  private emitTravelShareCompletion(
    requestKey: string,
    status: "opened" | "failed" = "opened",
  ): void {
    this.emitEvent("travelShareCompleted", { requestKey, status });
  }

  private emitEvent(type: BridgeEvent["type"], payload: BridgeEvent["payload"]): void {
    if (!this.documentId) throw new Error("BRIDGE_NOT_BOOTSTRAPPED");
    const event: BridgeEvent = {
      kind: "event",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: this.documentId,
      bridgeSessionId: this.sessionId,
      subscriptionId: "app-state",
      sequence: ++this.eventSequence,
      type,
      payload,
    };
    this.onmessage?.({ data: JSON.stringify(event) });
  }

  dispatchCount(type: ProductCommand["type"]): number {
    return this.posted.filter((request) => (
      request.method === "dispatch" &&
      (request.payload as unknown as ProductCommand).type === type
    )).length;
  }

  latestDispatch(type: ProductCommand["type"]): ProductCommand | null {
    for (let index = this.posted.length - 1; index >= 0; index -= 1) {
      const request = this.posted[index];
      if (request.method !== "dispatch") continue;
      const command = request.payload as unknown as ProductCommand;
      if (command.type === type) return command;
    }
    return null;
  }

  callCount(method: BridgeRequest["method"]): number {
    return this.posted.filter((request) => request.method === method).length;
  }
}

afterEach(() => vi.useRealTimers());

describe("WebView app shell", () => {
  it("shows controlled copy for incompatible bundle and missing System WebView", () => {
    expect(startupErrorMessage("UNSUPPORTED_PROTOCOL")).toBe(
      "Версия приложения несовместима с интерфейсом. Обновите приложение.",
    );
    expect(startupErrorMessage("NATIVE_BRIDGE_UNAVAILABLE")).toBe(
      "Android System WebView недоступен. Обновите системный компонент и приложение.",
    );
    expect(startupErrorMessage("INVALID_BOOTSTRAP")).toBe(
      "Не удалось открыть приложение.",
    );
  });

  it("announces startup loading and bootstrap failure without wrapping the retry control", () => {
    const retry = vi.fn();
    const { rerender } = render(<StartupLoading />);
    const loading = screen.getByRole("main", { name: "Загрузка" });
    expect(loading).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("status")).toHaveTextContent("Загрузка приложения");

    rerender(<StartupError message="Не удалось открыть приложение." retry={retry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Не удалось открыть приложение.");
    const retryButton = screen.getByRole("button", { name: "Повторить" });
    expect(screen.getByRole("alert")).not.toContainElement(retryButton);
    fireEvent.click(retryButton);
    expect(retry).toHaveBeenCalledOnce();
  });

  it("advances through the Android Create questions", async () => {
    window.history.replaceState({}, "", "/?fixture=create");
    render(<App />);

    expect(await screen.findByText("Кого хочешь создать?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ледяного дракона" }));
    expect(await screen.findByText("Как его будут звать?")).toBeInTheDocument();
  });

  it("retains the final Create frame through the native 220/120 root fade into Dashboard", async () => {
    const create = createMockSnapshot("create");
    const port = new InteractiveNativePort({
      ...create,
      create: {
        ...create.create!,
        step: 5,
        title: "",
        options: [],
        nextQuestion: null,
        phase: "formed",
        generation: "ready",
      },
    });
    window.gigagochiNative = port;
    const { container } = render(<App />);

    const creating = await screen.findByRole("main", { name: "Создаем друга" });
    const rootTransition = screen.getByRole("group", {
      name: "Переход между созданием и питомцем",
    });
    expect(rootTransition).toHaveAttribute("data-transition-active", "false");

    const dashboard = {
      ...createMockSnapshot("dashboard"),
      revision: "native-create-finished",
    };
    act(() => port.emitStateChanged(dashboard));

    const dashboardMain = container.querySelector<HTMLElement>(".screen--dashboard");
    expect(dashboardMain).not.toBeNull();
    expect(creating).toBeInTheDocument();
    expect(creating.closest(".route-transition__layer")).toHaveAttribute("aria-hidden", "true");
    expect(creating.closest(".route-transition__layer")).toHaveAttribute("inert");
    expect(dashboardMain!.closest(".route-transition__layer")).not.toHaveAttribute("aria-hidden");
    expect(dashboardMain!.closest(".route-transition__layer")).not.toHaveAttribute("inert");
    expect(dashboardMain).toHaveAttribute("tabindex", "-1");
    expect(dashboardMain).toHaveFocus();
    expect(rootTransition).toHaveAttribute("data-transition-active", "true");
    expect(rootTransition.style.getPropertyValue("--route-fade-in-duration")).toBe("220ms");
    expect(rootTransition.style.getPropertyValue("--route-fade-out-duration")).toBe("120ms");
    expect(rootTransition.style.getPropertyValue("--route-fade-easing")).toBe(
      "cubic-bezier(0.4, 0, 0.2, 1)",
    );

    await waitFor(() => expect(creating).not.toBeInTheDocument(), { timeout: 1_000 });
    expect(rootTransition).toHaveAttribute("data-transition-active", "false");
  });

  it("keeps cold-start Dashboard and reduced-motion Create → Dashboard instantaneous", async () => {
    const coldPort = new InteractiveNativePort(createMockSnapshot("dashboard"));
    window.gigagochiNative = coldPort;
    const cold = render(<App />);

    const coldDashboard = await waitFor(() => {
      const element = cold.container.querySelector<HTMLElement>(".screen--dashboard");
      expect(element).not.toBeNull();
      return element!;
    });
    const coldTransition = screen.getByRole("group", {
      name: "Переход между созданием и питомцем",
    });
    expect(coldTransition).toHaveAttribute("data-transition-active", "false");
    expect(coldTransition.querySelector(".route-transition__layer--animating")).toBeNull();
    expect(coldDashboard).not.toHaveFocus();
    cold.unmount();

    const create = {
      ...createMockSnapshot("create"),
      reducedMotion: true,
    };
    const reducedPort = new InteractiveNativePort(create);
    window.gigagochiNative = reducedPort;
    const reduced = render(<App />);
    expect(await screen.findByText("Кого хочешь создать?")).toBeInTheDocument();

    act(() => reducedPort.emitStateChanged({
      ...createMockSnapshot("dashboard"),
      revision: "native-reduced-dashboard",
      reducedMotion: true,
    }));

    expect(screen.queryByText("Кого хочешь создать?")).not.toBeInTheDocument();
    const reducedTransition = screen.getByRole("group", {
      name: "Переход между созданием и питомцем",
    });
    expect(reducedTransition).toHaveAttribute("data-transition-active", "false");
    expect(reduced.container.querySelector(".screen--dashboard")).toHaveFocus();
  });

  it("serializes five rapid pet taps against the latest native revision", async () => {
    render(<App />);
    const target = await screen.findByRole("button", { name: /Погладить/ });

    for (let tap = 0; tap < 5; tap += 1) fireEvent.click(target);

    await waitFor(() => {
      expect(screen.getByLabelText("Настроение: 97")).toBeInTheDocument();
    });
    expect(screen.getByRole("status")).toHaveTextContent("Приятно!");
  });

  it("opens the native-backed feed presentation mode", async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /Покормить/ }));
    expect(screen.getByRole("button", { name: "Ягодная миска" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Хрустящий лист" })).toBeInTheDocument();
  });

  it("reports Create custom readiness and closes it on a fenced system Back", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("create"));
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Свой вариант" }));
    expect(screen.getByTestId("create-custom")).toBeInTheDocument();
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(true));
    const ready = port.latestNavigationReady();
    if (!ready) throw new Error("READINESS_MISSING");

    port.emitSystemBack(ready.sequence);

    await waitFor(() => expect(screen.queryByTestId("create-custom")).not.toBeInTheDocument());
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(false));
  });

  it("ignores a stale system Back navigation sequence", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("create"));
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Свой вариант" }));
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(true));
    const ready = port.latestNavigationReady();
    if (!ready) throw new Error("READINESS_MISSING");

    port.emitSystemBack(Math.max(0, ready.sequence - 1));

    expect(screen.getByTestId("create-custom")).toBeInTheDocument();
  });

  it("closes a Dashboard inline mode once when duplicate Back events arrive rapidly", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("dashboard"));
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: /Поболтать/ }));
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(true));
    const ready = port.latestNavigationReady();
    if (!ready) throw new Error("READINESS_MISSING");

    port.emitSystemBack(ready.sequence);
    port.emitSystemBack(ready.sequence);

    await waitFor(() => expect(screen.queryByRole("button", { name: "Назад" })).not.toBeInTheDocument());
    await waitFor(() => expect(port.dispatchCount("DASHBOARD_CLOSE_MODE")).toBe(1));
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(false));
  });

  it("coalesces rapid drafts silently, keeps caret stable, and flushes before system Back", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("dashboard"), null, true);
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: /Нарядить/ }));
    const input = await screen.findByPlaceholderText("В футболку Metallica");
    fireEvent.change(input, { target: { value: "к" } });
    fireEvent.change(input, { target: { value: "красный" } });
    fireEvent.change(input, { target: { value: "красный шарф" } });
    (input as HTMLTextAreaElement).setSelectionRange(4, 4);

    await waitFor(() => expect(port.dispatchCount("DASHBOARD_UPDATE_DRAFT")).toBe(1));
    expect(input).not.toBeDisabled();
    expect(input).toHaveValue("красный шарф");
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(true));
    const ready = port.latestNavigationReady();
    if (!ready) throw new Error("READINESS_MISSING");
    port.emitSystemBack(ready.sequence);
    expect(port.dispatchCount("DASHBOARD_CLOSE_MODE")).toBe(0);

    act(() => port.releaseNextDraft());
    await waitFor(() => expect(port.dispatchCount("DASHBOARD_UPDATE_DRAFT")).toBe(2));
    expect(input).toHaveValue("красный шарф");
    expect((input as HTMLTextAreaElement).selectionStart).toBe(4);
    act(() => port.releaseNextDraft());

    await waitFor(() => expect(port.dispatchCount("DASHBOARD_CLOSE_MODE")).toBe(1));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Назад" })).not.toBeInTheDocument());
    const draftCommands = port.posted
      .filter((request) => request.method === "dispatch")
      .map((request) => request.payload as unknown as ProductCommand)
      .filter((command): command is Extract<ProductCommand, { type: "DASHBOARD_UPDATE_DRAFT" }> => (
        command.type === "DASHBOARD_UPDATE_DRAFT"
      ));
    expect(draftCommands.map((command) => command.payload.value)).toEqual([
      "к",
      "красный шарф",
    ]);

    fireEvent.click(screen.getByRole("button", { name: /Нарядить/ }));
    expect(await screen.findByPlaceholderText("В футболку Metallica"))
      .toHaveValue("красный шарф");
  });

  it("flushes the final rapid draft before submitting its exact text", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("dashboard"));
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: /В путешествие/ }));
    const input = await screen.findByPlaceholderText("На ночной рынок духов");
    fireEvent.change(input, { target: { value: "лес" } });
    fireEvent.change(input, { target: { value: "далёкий лес" } });
    fireEvent.change(input, { target: { value: "  далёкий лес у моря  " } });
    fireEvent.click(screen.getByRole("button", { name: "Отправить в путешествие" }));

    await waitFor(() => expect(port.dispatchCount("TRAVEL_SUBMIT")).toBe(1));
    expect(port.latestDispatch("TRAVEL_SUBMIT")).toMatchObject({
      payload: { prompt: "далёкий лес у моря" },
    });
    const dispatches = port.posted
      .filter((request) => request.method === "dispatch")
      .map((request) => request.payload as unknown as ProductCommand);
    const submitIndex = dispatches.findIndex((command) => command.type === "TRAVEL_SUBMIT");
    const lastDraftIndex = dispatches.reduce(
      (found, command, index) => command.type === "DASHBOARD_UPDATE_DRAFT" ? index : found,
      -1,
    );
    expect(lastDraftIndex).toBeGreaterThanOrEqual(0);
    expect(lastDraftIndex).toBeLessThan(submitIndex);
    expect(dispatches[lastDraftIndex]).toMatchObject({
      type: "DASHBOARD_UPDATE_DRAFT",
      payload: { mode: "travel", value: "  далёкий лес у моря  " },
    });
  });

  it("never renders a raw command bridge error banner", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("dashboard"), "PET_TAP");
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: /Погладить/ }));
    await waitFor(() => expect(port.dispatchCount("PET_TAP")).toBe(1));

    expect(screen.queryByText("WRONG_STAGE")).not.toBeInTheDocument();
  });

  it("requests notification permission only once after product bootstrap", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("dashboard"));
    window.gigagochiNative = port;
    render(<App />);

    await waitFor(() => expect(port.callCount("requestNotificationPermission")).toBe(1));
    await waitFor(() => expect(port.callCount("navigationReady")).toBeGreaterThan(0));
    expect(port.callCount("requestNotificationPermission")).toBe(1);
  });

  it("renders the exact Events badge and routes its click through NAVIGATE", async () => {
    const base = createMockSnapshot("dashboard");
    const port = new InteractiveNativePort({
      ...base,
      events: { ...base.events!, badgeCount: 123 },
    });
    window.gigagochiNative = port;
    render(<App />);

    const events = await screen.findByRole("button", {
      name: "События, требуют внимания: 123",
    });
    expect(events).toHaveTextContent("99+");
    fireEvent.click(events);

    await waitFor(() => expect(port.dispatchCount("NAVIGATE")).toBe(1));
    expect(port.latestDispatch("NAVIGATE")).toMatchObject({
      payload: { route: "events" },
    });
  });

  it("keeps the exact Dashboard and video nodes retained and pauses media under Events", async () => {
    const pause = vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => undefined);
    const base = createMockSnapshot("dashboard");
    const port = new InteractiveNativePort(base);
    window.gigagochiNative = port;
    const { container } = render(<App />);

    const dashboard = await waitFor(() => {
      const element = container.querySelector<HTMLElement>(".screen--dashboard");
      expect(element).not.toBeNull();
      return element!;
    });
    const video = dashboard.querySelector("video");
    expect(video).not.toBeNull();

    act(() => port.emitStateChanged({
      ...base,
      revision: "native-events-1",
      route: "events",
    }));

    expect(await screen.findByRole("heading", { name: "События" })).toBeInTheDocument();
    expect(container.querySelector(".screen--dashboard")).toBe(dashboard);
    expect(dashboard.querySelector("video")).toBe(video);
    const dashboardLayer = dashboard.closest(".retained-dashboard-stack__dashboard");
    expect(dashboardLayer).toHaveAttribute("aria-hidden", "true");
    expect(dashboardLayer).toHaveAttribute("inert");
    expect(pause).toHaveBeenCalled();
  });

  it.each(["events", "story"] as const)(
    "moves focus out of an inert Dashboard for %s and restores its trigger on Back",
    async (route) => {
      vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
      const base = createMockSnapshot("dashboard");
      const port = new InteractiveNativePort(base);
      window.gigagochiNative = port;
      const { container } = render(<App />);

      const trigger = await screen.findByRole("button", { name: "События" });
      trigger.focus();
      expect(trigger).toHaveFocus();
      const target = createMockSnapshot(route);
      act(() => port.emitStateChanged({
        ...target,
        revision: `focus-${route}`,
      }));

      const routeMain = await waitFor(() => {
        const element = container.querySelector<HTMLElement>(
          route === "events" ? ".event-history" : ".scheduled-story",
        );
        expect(element).not.toBeNull();
        return element!;
      });
      await waitFor(() => expect(routeMain).toHaveFocus());
      expect(document.activeElement?.closest("[inert]")).toBeNull();

      act(() => port.emitStateChanged({
        ...base,
        revision: `focus-dashboard-from-${route}`,
        route: "dashboard",
      }));
      await waitFor(() => expect(trigger).toHaveFocus());
      expect(document.activeElement?.closest("[inert]")).toBeNull();
    },
  );

  it.each(["events", "story"] as const)(
    "defers a retained Dashboard reply under %s and resumes it exactly once on return",
    async (route) => {
      vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
      const base = createMockSnapshot(route);
      const port = new InteractiveNativePort(base);
      window.gigagochiNative = port;
      const { container } = render(<App />);
      const dashboard = await waitFor(() => {
        const element = container.querySelector<HTMLElement>(".screen--dashboard");
        expect(element).not.toBeNull();
        return element!;
      });
      const video = dashboard.querySelector("video");

      vi.useFakeTimers();
      const timersBeforeReply = vi.getTimerCount();
      const hidden = {
        ...base,
        revision: `hidden-reply-${route}`,
        dashboard: {
          ...base.dashboard!,
          reply: {
            source: "chat" as const,
            requestKey: `hidden-${route}`,
            portions: ["Первая", "Вторая"],
            portionIndex: 0,
            hasNextPortion: true,
            autoAdvanceDelayMillis: 6_000,
          },
        },
      };
      await act(async () => {
        port.emitStateChanged(hidden);
        await Promise.resolve();
      });

      expect(port.dispatchCount("CHAT_REPLY_PRESENTED")).toBe(0);
      expect(port.dispatchCount("REPLY_ADVANCE")).toBe(0);
      expect(vi.getTimerCount()).toBe(timersBeforeReply);
      await act(async () => {
        vi.advanceTimersByTime(60_000);
        await Promise.resolve();
      });
      expect(port.dispatchCount("CHAT_REPLY_PRESENTED")).toBe(0);
      expect(port.dispatchCount("REPLY_ADVANCE")).toBe(0);

      await act(async () => {
        port.emitStateChanged({
          ...hidden,
          revision: `dashboard-return-${route}`,
          route: "dashboard",
          story: null,
        });
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(container.querySelector(".screen--dashboard")).toBe(dashboard);
      expect(dashboard.querySelector("video")).toBe(video);
      expect(port.dispatchCount("CHAT_REPLY_PRESENTED")).toBe(1);

      act(() => vi.advanceTimersByTime(5_999));
      expect(port.dispatchCount("REPLY_ADVANCE")).toBe(0);
      await act(async () => {
        vi.advanceTimersByTime(1);
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(port.dispatchCount("REPLY_ADVANCE")).toBe(1);
      await act(async () => {
        vi.advanceTimersByTime(60_000);
        await Promise.resolve();
      });
      expect(port.dispatchCount("CHAT_REPLY_PRESENTED")).toBe(1);
      expect(port.dispatchCount("REPLY_ADVANCE")).toBe(1);
      vi.useRealTimers();
    },
  );

  it("marks Events once, opens Story, and shares only the travel request key", async () => {
    const base = createMockSnapshot("events");
    const story = localStoryFixture({ storyId: "needs-help" });
    const travel = travelVideoFixture("share-me", { completedAtEpochMillis: 500 });
    const port = new InteractiveNativePort({
      ...base,
      events: {
        stories: [story],
        travelVideos: [travel],
        badgeCount: 2,
        latestEventAtEpochMillis: 500,
        lastViewedAtEpochMillis: null,
        initialFocusTravelRequestKey: null,
      },
    });
    port.holdTravelShareCompletions();
    window.gigagochiNative = port;
    render(<App />);

    await screen.findByRole("heading", { name: "События" });
    await waitFor(() => expect(port.dispatchCount("EVENTS_MARK_VIEWED")).toBe(1));
    expect(port.latestDispatch("EVENTS_MARK_VIEWED")).toMatchObject({
      payload: { viewedAt: 500 },
    });

    fireEvent.click(screen.getByRole("button", { name: "Поделиться видео" }));
    await waitFor(() => expect(port.callCount("shareTravelVideo")).toBe(1));
    const share = port.posted.find((request) => request.method === "shareTravelVideo");
    expect(share?.payload).toEqual({ requestKey: "share-me" });
    expect(screen.getByRole("button", { name: "Подготовка видео" })).toBeDisabled();

    act(() => port.completeNextTravelShare());
    await screen.findByRole("button", { name: "Поделиться видео" });

    fireEvent.click(screen.getByRole("button", { name: "Помочь" }));
    await waitFor(() => expect(port.dispatchCount("STORY_OPEN")).toBe(1));
    expect(port.latestDispatch("STORY_OPEN")).toMatchObject({
      payload: { storyId: "needs-help" },
    });
    expect(port.dispatchCount("EVENTS_MARK_VIEWED")).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "Назад" }));
    await waitFor(() => expect(port.dispatchCount("BACK")).toBe(1));
    await waitFor(() => expect(port.callCount("feedback")).toBe(1));
    const feedbackIndex = port.posted.findIndex((request) => request.method === "feedback");
    const backIndex = port.posted.findIndex((request) => (
      request.method === "dispatch" &&
      (request.payload as unknown as ProductCommand).type === "BACK"
    ));
    expect(port.posted[feedbackIndex].payload).toMatchObject({ kind: "buttonPress" });
    expect(feedbackIndex).toBeLessThan(backIndex);
  });

  it.each([
    ["question", "Подойти", "STORY_CHOOSE"],
    ["retryable", "Подойти", "STORY_RETRY"],
    ["result", "Завершить", "STORY_FINISH"],
  ] as const)("routes %s Story action through %s", async (phase, buttonLabel, commandType) => {
    const base = createMockSnapshot("story");
    const result = storyOutcomeFixture();
    const story = phase === "retryable"
      ? storyScreenFixture({
          phase,
          durableRequestKey: "choice-fixture",
          pendingChoice: "Подойти",
          error: "Попробуй снова",
        })
      : phase === "result"
        ? storyScreenFixture({
            phase,
            durableRequestKey: result.requestKey,
            result,
          })
        : storyScreenFixture();
    const port = new InteractiveNativePort({ ...base, story });
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: buttonLabel }));
    await waitFor(() => expect(port.dispatchCount(commandType)).toBe(1));
    expect(port.latestDispatch(commandType)).toMatchObject({
      payload: phase === "question"
        ? { storyId: story.story.storyId, choice: "Подойти" }
        : { storyId: story.story.storyId },
    });
    if (phase === "question") {
      expect(await screen.findByRole("status", { name: "Персонаж думает" })).toHaveAttribute(
        "data-frame",
        "1",
      );
      await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(true));
      fireEvent.click(screen.getByRole("button", { name: "Назад" }));
      await waitFor(() => expect(port.dispatchCount("BACK")).toBe(1));
      await waitFor(() => expect(port.callCount("feedback")).toBe(1));
      const feedbackIndex = port.posted.findIndex((request) => request.method === "feedback");
      const backIndex = port.posted.findIndex((request) => (
        request.method === "dispatch" &&
        (request.payload as unknown as ProductCommand).type === "BACK"
      ));
      expect(port.posted[feedbackIndex].payload).toMatchObject({ kind: "buttonPress" });
      expect(feedbackIndex).toBeLessThan(backIndex);
    }
  });

  it("routes the onboarding bat action to travel without opening Dashboard travel mode", async () => {
    const base = createMockSnapshot("dashboard");
    const port = new InteractiveNativePort({
      ...base,
      firstSession: {
        stage: "awaiting-travel",
        allowedAction: "travel",
        messagePortions: ["Помоги малышу"],
        selectedDestination: null,
      },
    });
    window.gigagochiNative = port;
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Помочь летучей мыши" }));
    await waitFor(() => expect(port.dispatchCount("NAVIGATE")).toBe(1));
    expect(port.latestDispatch("NAVIGATE")).toMatchObject({ payload: { route: "travel" } });
    expect(port.dispatchCount("DASHBOARD_OPEN_MODE")).toBe(0);
  });

  it("dispatches one BACK for exact duplicate system Back and ignores a stale Events fence", async () => {
    const port = new InteractiveNativePort(createMockSnapshot("events"));
    window.gigagochiNative = port;
    render(<App />);

    await screen.findByRole("heading", { name: "События" });
    await waitFor(() => expect(port.latestNavigationReady()?.canHandleBack).toBe(true));
    const ready = port.latestNavigationReady();
    if (!ready) throw new Error("READINESS_MISSING");

    port.emitSystemBack(Math.max(0, ready.sequence - 1));
    expect(port.dispatchCount("BACK")).toBe(0);
    port.emitSystemBack(ready.sequence);
    port.emitSystemBack(ready.sequence);
    await waitFor(() => expect(port.dispatchCount("BACK")).toBe(1));
  });

  it("renders authoritative choicePending as inert thinking while Back remains available", async () => {
    const base = createMockSnapshot("story");
    const port = new InteractiveNativePort({
      ...base,
      story: storyScreenFixture({
        phase: "choicePending",
        durableRequestKey: "choice-pending",
        pendingChoice: "Подойти",
      }),
    });
    window.gigagochiNative = port;
    const { container } = render(<App />);

    expect(await screen.findByRole("status", { name: "Персонаж думает" })).toBeInTheDocument();
    expect(container.querySelector(".scheduled-story__scroll")).toHaveAttribute(
      "aria-hidden",
      "true",
    );
    const back = screen.getByRole("button", { name: "Назад" });
    expect(back).toBeEnabled();
    fireEvent.click(back);
    await waitFor(() => expect(port.dispatchCount("BACK")).toBe(1));
  });
});
