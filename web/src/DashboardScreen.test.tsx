import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type {
  DashboardReplySnapshot,
  DashboardSnapshot,
  FirstSessionSnapshot,
  JsonValue,
  PetSnapshot,
  ProductCommandType,
} from "./contracts";
import {
  DashboardScreen,
  dashboardConversationGeometry,
  dashboardViewportGeometry,
} from "./DashboardScreen";

const pet: PetSnapshot = {
  name: "Луна",
  stageLabel: "Малыш",
  experience: 240,
  hunger: 74,
  happiness: 92,
  energy: 61,
  message: "Привет",
  petTapProgress: 0,
  media: {
    videoRef: "/res/pet.mp4",
    posterRef: "/res/pet.webp",
    sadVideoRef: null,
    happyVideoRef: null,
  },
};

function dashboard(reply: DashboardReplySnapshot | null = null): DashboardSnapshot {
  return {
    reply,
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
  };
}

function renderDashboard(options: {
  mode?: "idle" | "chat" | "feed" | "outfit" | "travel";
  reply?: DashboardReplySnapshot | null;
  snapshot?: DashboardSnapshot;
  firstSession?: FirstSessionSnapshot | null;
  dispatch?: (type: ProductCommandType, payload?: JsonValue) => Promise<void>;
  feedback?: () => void;
  nativeFeedback?: Parameters<typeof DashboardScreen>[0]["feedback"];
  reducedMotion?: boolean;
} = {}) {
  const dispatch = options.dispatch ?? vi.fn(async () => undefined);
  return {
    dispatch,
    ...render(
      <DashboardScreen
        pet={pet}
        mode={options.mode ?? "idle"}
        busy={false}
        petTapFeedback={null}
        reducedMotion={options.reducedMotion ?? false}
        firstSession={options.firstSession ?? null}
        dashboard={options.snapshot ?? dashboard(options.reply ?? null)}
        onOpenMode={vi.fn()}
        onCloseMode={vi.fn()}
        feedback={options.nativeFeedback ?? vi.fn()}
        dispatch={dispatch}
        onPetTapInteractionFeedback={options.feedback}
      />,
    ),
  };
}

describe("dashboard exact interaction layer", () => {
  it("uses the interruptible Android Dashboard spring for action presses", () => {
    vi.useFakeTimers();
    const { unmount } = renderDashboard();
    const button = screen.getByRole("button", { name: "Поболтать" });

    fireEvent.pointerDown(button, { button: 0 });
    expect(button).toHaveAttribute("data-press-phase", "pressing");
    expect(button.style.transform).toBe("scale(0.920000)");
    expect(button.style.transition).toContain("linear(");

    act(() => vi.advanceTimersByTime(12));
    fireEvent.pointerUp(button, { button: 0 });
    expect(button).toHaveAttribute("data-press-phase", "releasing");
    expect(button.style.transform).toBe("scale(1.000000)");
    expect(button.style.transition).toContain("linear(");

    act(() => vi.runAllTimers());
    expect(button).toHaveAttribute("data-press-phase", "idle");
    unmount();
    vi.useRealTimers();
  });

  it("keeps viewport chrome and composer outside the scaled reference plane", () => {
    const { container } = renderDashboard({ mode: "outfit" });
    const plane = container.querySelector(".reference-plane") as HTMLElement;
    const level = screen.getByText("Уровень: Малыш").closest(".pet-level") as HTMLElement;
    const back = screen.getByRole("button", { name: "Назад" });
    const input = container.querySelector(".dashboard-input") as HTMLElement;
    const prompt = screen.getByRole("status");
    expect(prompt).toHaveTextContent("Во что мне нарядиться?");

    expect(plane).not.toContainElement(level);
    expect(plane).not.toContainElement(back);
    expect(plane).not.toContainElement(input);
    expect(plane).toContainElement(prompt);
  });

  it("matches native action and feed geometry at baseline and compact safe viewport sizes", () => {
    expect(dashboardViewportGeometry(874, 0, 1)).toEqual({
      visibleReferenceBottom: 874,
      actionTop: 762,
      feedTop: 692.203,
    });

    const compactScale = Math.max(411 / 402, 823 / 874);
    const compact = dashboardViewportGeometry(823, 48, compactScale);
    expect(compact.visibleReferenceBottom).toBeCloseTo(758.029197, 6);
    expect(compact.actionTop).toBeCloseTo(683.826197, 6);
    expect(compact.feedTop).toBeCloseTo(614.029197, 6);
    expect(compact.actionTop + 58.203 + 16).toBeCloseTo(compact.visibleReferenceBottom, 6);
  });

  it("anchors dialogue and thinking geometry to the measured viewport composer surface", () => {
    const scale = Math.max(411 / 402, 823 / 874);
    const geometry = dashboardConversationGeometry(600, 10, scale, 4);

    expect(geometry.dialogueTop).toBeCloseTo(
      geometry.inputTopInReference - 156 - 48,
      6,
    );
    expect(geometry.thinkingTop + 55.5).toBeCloseTo(
      geometry.inputTopInReference - 24,
      6,
    );
  });

  it("advances an unrevealed reply from a scene tap and cancels a moved gesture", () => {
    const dispatch = vi.fn(async () => undefined);
    const reply: DashboardReplySnapshot = {
      source: "chat",
      requestKey: "chat-1",
      portions: ["Первая", "Вторая"],
      portionIndex: 0,
      hasNextPortion: true,
      autoAdvanceDelayMillis: 6_000,
    };
    const { container, rerender } = renderDashboard({ mode: "chat", reply, dispatch });
    const plane = container.querySelector(".reference-plane") as HTMLElement;
    fireEvent.pointerDown(plane, { pointerId: 1, button: 0, clientX: 100, clientY: 200 });
    fireEvent.pointerUp(plane, { pointerId: 1, clientX: 100, clientY: 200 });
    expect(dispatch).toHaveBeenCalledWith("REPLY_ADVANCE", { requestKey: "chat-1" });

    dispatch.mockClear();
    rerender(
      <DashboardScreen
        pet={pet}
        mode="chat"
        busy={false}
        petTapFeedback={null}
        reducedMotion={false}
        firstSession={null}
        dashboard={dashboard({ ...reply, requestKey: "chat-2" })}
        onOpenMode={vi.fn()}
        onCloseMode={vi.fn()}
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    const nextPlane = container.querySelector(".reference-plane") as HTMLElement;
    fireEvent.pointerDown(nextPlane, { pointerId: 2, button: 0, clientX: 100, clientY: 200 });
    fireEvent.pointerMove(nextPlane, { pointerId: 2, clientX: 120, clientY: 200 });
    fireEvent.pointerUp(nextPlane, { pointerId: 2, clientX: 120, clientY: 200 });
    expect(dispatch).not.toHaveBeenCalledWith("REPLY_ADVANCE", { requestKey: "chat-2" });
  });

  it("treats a completed onboarding session as ordinary and applies native scroll offset once", () => {
    const completed: FirstSessionSnapshot = {
      stage: "completed",
      allowedAction: null,
      messagePortions: ["Готово"],
      selectedDestination: null,
    };
    const { container } = renderDashboard({ firstSession: completed });
    const actions = container.querySelector(".dashboard-actions") as HTMLElement;
    expect(actions).not.toHaveClass("dashboard-actions--onboarding");
    expect(actions.scrollLeft).toBe(68);
    expect(screen.getAllByRole("button")).toEqual(expect.arrayContaining([
      screen.getByRole("button", { name: "Поболтать" }),
      screen.getByRole("button", { name: "Покормить" }),
      screen.getByRole("button", { name: "В путешествие" }),
    ]));
  });

  it("renders outfit prompts through animated character dialogue and exposes a haptic seam", async () => {
    const { rerender } = renderDashboard({ mode: "outfit" });
    const prompt = screen.getByRole("status");
    expect(prompt).toHaveTextContent("Во что мне нарядиться?");
    expect(prompt).toHaveClass("pet-message--prompt");
    expect(screen.queryByRole("button", { name: "Во что мне нарядиться?" })).not.toBeInTheDocument();
    expect(document.querySelector(".dashboard-input h2")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Experience: 240")).toBeInTheDocument();

    const feedback = vi.fn();
    rerender(
      <DashboardScreen
        pet={pet}
        mode="idle"
        busy={false}
        petTapFeedback={null}
        reducedMotion={false}
        firstSession={null}
        dashboard={dashboard()}
        onOpenMode={vi.fn()}
        onCloseMode={vi.fn()}
        feedback={vi.fn()}
        dispatch={vi.fn(async () => undefined)}
        onPetTapInteractionFeedback={feedback}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Погладить Луна" }));
    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
  });

  it("moves keyboard focus into Feed and restores its Dashboard trigger on close", () => {
    const onOpenMode = vi.fn();
    const onCloseMode = vi.fn();
    const shared = {
      pet,
      busy: false,
      petTapFeedback: null,
      reducedMotion: true,
      firstSession: null,
      dashboard: dashboard(),
      onOpenMode,
      onCloseMode,
      feedback: vi.fn(),
      dispatch: vi.fn(async () => undefined),
    };
    const { rerender } = render(<DashboardScreen {...shared} mode="idle" />);
    const trigger = screen.getByRole("button", { name: "Покормить" });
    trigger.focus();
    fireEvent.click(trigger);
    expect(onOpenMode).toHaveBeenCalledWith("feed");

    rerender(<DashboardScreen {...shared} mode="feed" />);
    expect(screen.getByRole("button", { name: "Ягодная миска" })).toHaveFocus();

    const back = screen.getByRole("button", { name: "Назад" });
    back.focus();
    fireEvent.click(back);
    expect(onCloseMode).toHaveBeenCalledOnce();
    rerender(<DashboardScreen {...shared} mode="idle" />);

    expect(screen.getByRole("button", { name: "Покормить" })).toHaveFocus();
  });

  it("keeps accepted pet feedback but skips heart particles with reduced motion", async () => {
    const animationFrame = vi.spyOn(window, "requestAnimationFrame");
    const feedback = vi.fn();
    renderDashboard({ reducedMotion: true, feedback });

    fireEvent.click(screen.getByRole("button", { name: "Погладить Луна" }));

    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
    expect(animationFrame).not.toHaveBeenCalled();
    animationFrame.mockRestore();
  });

  it("matches native pet touch cancellation for slop, bounds exit, and multitouch", () => {
    const dispatch = vi.fn(async () => undefined);
    const rect = (left: number, top: number, width: number, height: number) => ({
      left,
      top,
      right: left + width,
      bottom: top + height,
      x: left,
      y: top,
      width,
      height,
      toJSON: () => ({}),
    }) as DOMRect;
    const boundsSpy = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: HTMLElement) {
        if (this.classList.contains("reference-plane")) return rect(0, 0, 402, 874);
        if (this.classList.contains("pet-hit-target")) {
          return rect(37.125, 122.906, 327.75, 450.656);
        }
        return rect(0, 0, 0, 0);
      });
    renderDashboard({ dispatch });
    const target = screen.getByRole("button", { name: "Погладить Луна" });

    fireEvent.pointerDown(target, {
      pointerId: 1,
      pointerType: "touch",
      isPrimary: true,
      button: 0,
      clientX: 100,
      clientY: 200,
    });
    fireEvent.pointerMove(target, {
      pointerId: 1,
      pointerType: "touch",
      isPrimary: true,
      clientX: 109,
      clientY: 200,
    });
    fireEvent.pointerUp(target, {
      pointerId: 1,
      pointerType: "touch",
      isPrimary: true,
      clientX: 109,
      clientY: 200,
    });

    fireEvent.pointerDown(target, {
      pointerId: 2,
      pointerType: "touch",
      isPrimary: true,
      button: 0,
      clientX: 100,
      clientY: 200,
    });
    fireEvent.pointerMove(target, {
      pointerId: 2,
      pointerType: "touch",
      isPrimary: true,
      clientX: 30,
      clientY: 200,
    });
    fireEvent.pointerUp(target, {
      pointerId: 2,
      pointerType: "touch",
      isPrimary: true,
      clientX: 30,
      clientY: 200,
    });

    fireEvent.pointerDown(target, {
      pointerId: 3,
      pointerType: "touch",
      isPrimary: true,
      button: 0,
      clientX: 100,
      clientY: 200,
    });
    fireEvent.pointerDown(window, {
      pointerId: 4,
      pointerType: "touch",
      isPrimary: false,
      button: 0,
      clientX: 120,
      clientY: 220,
    });
    fireEvent.pointerUp(window, {
      pointerId: 4,
      pointerType: "touch",
      isPrimary: false,
      clientX: 120,
      clientY: 220,
    });
    fireEvent.pointerUp(target, {
      pointerId: 3,
      pointerType: "touch",
      isPrimary: true,
      clientX: 100,
      clientY: 200,
    });

    expect(dispatch).not.toHaveBeenCalled();
    boundsSpy.mockRestore();
  });

  it("accepts one primary pet tap, ignores its synthetic click, and keeps keyboard activation", async () => {
    const dispatch = vi.fn(async () => undefined);
    const feedback = vi.fn();
    const rect = (left: number, top: number, width: number, height: number) => ({
      left,
      top,
      right: left + width,
      bottom: top + height,
      x: left,
      y: top,
      width,
      height,
      toJSON: () => ({}),
    }) as DOMRect;
    const boundsSpy = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: HTMLElement) {
        if (this.classList.contains("reference-plane")) return rect(0, 0, 402, 874);
        if (this.classList.contains("pet-hit-target")) {
          return rect(37.125, 122.906, 327.75, 450.656);
        }
        return rect(0, 0, 0, 0);
      });
    renderDashboard({ dispatch, feedback });
    const target = screen.getByRole("button", { name: "Погладить Луна" });

    fireEvent.pointerDown(target, {
      pointerId: 1,
      pointerType: "touch",
      isPrimary: true,
      button: 0,
      clientX: 100,
      clientY: 200,
    });
    fireEvent.pointerUp(target, {
      pointerId: 1,
      pointerType: "touch",
      isPrimary: true,
      clientX: 100,
      clientY: 200,
    });
    fireEvent.click(target, { detail: 1 });

    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(1));
    expect(feedback).toHaveBeenCalledTimes(1);

    target.focus();
    fireEvent.keyDown(target, { key: "Enter" });
    fireEvent.click(target, { detail: 0 });
    fireEvent.keyUp(target, { key: "Enter" });

    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(2));
    expect(feedback).toHaveBeenCalledTimes(2);
    expect(target).toHaveFocus();
    boundsSpy.mockRestore();
  });

  it("does not commit pet interaction feedback when native dispatch rejects the tap", async () => {
    const dispatch = vi.fn().mockRejectedValue(new Error("LOCAL_DATA_ERROR"));
    const feedback = vi.fn();
    renderDashboard({ dispatch, feedback });
    const target = screen.getByRole("button", { name: "Погладить Луна" });

    fireEvent.click(target, { detail: 0 });

    await waitFor(() => expect(dispatch).toHaveBeenCalledWith("PET_TAP"));
    await act(async () => Promise.resolve());
    expect(feedback).not.toHaveBeenCalled();
  });

  it("projects outfit and travel composer state from their dedicated DTOs", () => {
    const outfit = dashboard();
    outfit.outfit = {
      draft: "серебряный плащ",
      error: null,
      activeRequestKey: "outfit-1",
      thinking: true,
      experienceCost: 275,
      pending: {
        requestKey: "outfit-1",
        status: "pending",
        prompt: "серебряный плащ",
        displayItem: "Серебряный плащ",
        experienceCost: 275,
      },
    };
    const { rerender } = renderDashboard({ mode: "outfit", snapshot: outfit });
    expect(screen.getByPlaceholderText("В футболку Metallica")).toHaveValue("серебряный плащ");
    expect(screen.getByPlaceholderText("В футболку Metallica")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Создать наряд" })).toBeDisabled();
    expect(screen.queryByText("Наряд создаётся: серебряный плащ")).not.toBeInTheDocument();

    const travel = dashboard();
    travel.travel = {
      draft: "",
      error: null,
      activeRequestKey: null,
      thinking: false,
      pending: {
        requestKey: "travel-1",
        status: "attached",
        prompt: "ночной рынок духов",
      },
    };
    rerender(
      <DashboardScreen
        pet={pet}
        mode="travel"
        busy={false}
        petTapFeedback={null}
        reducedMotion={false}
        firstSession={null}
        dashboard={travel}
        onOpenMode={vi.fn()}
        onCloseMode={vi.fn()}
        feedback={vi.fn()}
        dispatch={vi.fn(async () => undefined)}
      />,
    );
    expect(screen.queryByText("Путешествие готовится: ночной рынок духов")).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText("На ночной рынок духов")).toBeDisabled();
  });

  it("routes action and contextual navigation presses to Android button audio", () => {
    const nativeFeedback = vi.fn();
    const onOpenMode = vi.fn();
    const { rerender } = render(
      <DashboardScreen
        pet={pet}
        mode="idle"
        busy={false}
        petTapFeedback={null}
        reducedMotion={false}
        firstSession={null}
        dashboard={dashboard()}
        onOpenMode={onOpenMode}
        onCloseMode={vi.fn()}
        feedback={nativeFeedback}
        dispatch={vi.fn(async () => undefined)}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Поболтать" }));
    expect(nativeFeedback).toHaveBeenLastCalledWith("dashboardAction");
    expect(onOpenMode).toHaveBeenCalledWith("chat");

    const onCloseMode = vi.fn();
    rerender(
      <DashboardScreen
        pet={pet}
        mode="chat"
        busy={false}
        petTapFeedback={null}
        reducedMotion={false}
        firstSession={null}
        dashboard={dashboard()}
        onOpenMode={onOpenMode}
        onCloseMode={onCloseMode}
        feedback={nativeFeedback}
        dispatch={vi.fn(async () => undefined)}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Назад" }));
    expect(nativeFeedback).toHaveBeenLastCalledWith("buttonPress");
    expect(onCloseMode).toHaveBeenCalledTimes(1);
  });
});
