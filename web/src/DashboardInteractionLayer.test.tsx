import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  DashboardReplySnapshot,
  DashboardSnapshot,
  FirstSessionSnapshot,
  JsonValue,
  ProductCommandType,
} from "./contracts";
import { AnimatedDialogueText } from "./FirstSessionDialogue";
import {
  ConversationInput,
  FeedModeLayer,
  ThinkingIndicator,
  useDashboardReplyPresentation,
} from "./DashboardInteractionLayer";

type Dispatch = (type: ProductCommandType, payload?: JsonValue) => Promise<void>;

function dashboardSnapshot(overrides: Partial<DashboardSnapshot> = {}): DashboardSnapshot {
  return {
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
      audioIndex: 0,
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
    ...overrides,
  };
}

const firstSession: FirstSessionSnapshot = {
  stage: "awaiting-chat-followup",
  allowedAction: "chat",
  messagePortions: ["Привет"],
  selectedDestination: null,
};

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("dashboard feed motion", () => {
  it("uses pulse identity and lets the latest feed interrupt the previous animation", () => {
    vi.useFakeTimers();
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const initial = dashboardSnapshot();
    const { container, rerender } = render(
      <FeedModeLayer dashboard={initial} reducedMotion={false} feedback={vi.fn()} dispatch={dispatch} />,
    );

    rerender(
      <FeedModeLayer
        dashboard={dashboardSnapshot({
          feed: { ...initial.feed, activeFood: "berry-bowl", pulseId: 1, thinking: true },
        })}
        reducedMotion={false}
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    expect(screen.getByRole("button", { name: "Ягодная миска" })).toHaveAttribute("data-phase", "consuming");
    expect(container.querySelector(".feed-pulse")).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(180));
    expect(screen.getByRole("button", { name: "Ягодная миска" })).toHaveAttribute("data-phase", "reappearing");

    rerender(
      <FeedModeLayer
        dashboard={dashboardSnapshot({
          feed: { ...initial.feed, activeFood: "leaf-crunch", pulseId: 2, thinking: true },
        })}
        reducedMotion={false}
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    expect(screen.getByRole("button", { name: "Ягодная миска" })).toHaveAttribute("data-phase", "idle");
    expect(screen.getByRole("button", { name: "Хрустящий лист" })).toHaveAttribute("data-phase", "consuming");

    act(() => vi.advanceTimersByTime(400));
    expect(screen.getByRole("button", { name: "Хрустящий лист" })).toHaveAttribute("data-phase", "idle");
    expect(container.querySelector(".feed-pulse")).not.toBeInTheDocument();
  });

  it("keeps reduced-motion feedback static and exposes a live confirmation", () => {
    vi.useFakeTimers();
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const initial = dashboardSnapshot();
    const { container, rerender } = render(
      <FeedModeLayer dashboard={initial} reducedMotion feedback={vi.fn()} dispatch={dispatch} />,
    );
    rerender(
      <FeedModeLayer
        dashboard={dashboardSnapshot({
          feed: { ...initial.feed, activeFood: "leaf-crunch", pulseId: 7, thinking: true },
        })}
        reducedMotion
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );

    expect(screen.getByRole("button", { name: "Хрустящий лист" })).toHaveAttribute("data-phase", "consuming");
    expect(container.querySelector(".feed-pulse--reduced")).toBeInTheDocument();
    expect(screen.getByText("Питомец получил лист")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(180));
    expect(screen.getByRole("button", { name: "Хрустящий лист" })).toHaveAttribute("data-phase", "reappearing");
    act(() => vi.advanceTimersByTime(220));
    expect(container.querySelector(".feed-pulse--reduced")).not.toBeInTheDocument();
  });

  it("accepts tap and in-scene drop, but cancels a drop outside the native tolerance", () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const feedback = vi.fn();
    const bounds = (left: number, top: number, width: number, height: number) => ({
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
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains("reference-plane")) return bounds(0, 0, 402, 874);
      if (this.getAttribute("data-food") === "berry-bowl") return bounds(88, 692, 76, 94);
      return bounds(0, 0, 0, 0);
    });

    render(
      <div className="reference-plane">
        <FeedModeLayer dashboard={dashboardSnapshot()} reducedMotion={false} feedback={feedback} dispatch={dispatch} />
      </div>,
    );
    const berry = screen.getByRole("button", { name: "Ягодная миска" });

    fireEvent.click(berry);
    expect(dispatch).toHaveBeenLastCalledWith("FEED_CONSUME", { food: "berry-bowl" });
    expect(feedback).toHaveBeenCalledTimes(1);
    expect(feedback).toHaveBeenLastCalledWith("buttonPress");

    fireEvent.pointerDown(berry, { pointerId: 1, button: 0, clientX: 126, clientY: 739 });
    fireEvent.pointerMove(berry, { pointerId: 1, clientX: 200, clientY: 400 });
    fireEvent.pointerUp(berry, { pointerId: 1, clientX: 200, clientY: 400 });
    expect(dispatch).toHaveBeenCalledTimes(2);
    expect(feedback).toHaveBeenCalledTimes(1);

    fireEvent.pointerDown(berry, { pointerId: 2, button: 0, clientX: 126, clientY: 739 });
    fireEvent.pointerMove(berry, { pointerId: 2, clientX: 1_200, clientY: 1_100 });
    fireEvent.pointerUp(berry, { pointerId: 2, clientX: 1_200, clientY: 1_100 });
    expect(dispatch).toHaveBeenCalledTimes(2);
  });

  it("waits for the authoritative pulse and recovers the token and error after dispatch rejection", async () => {
    let rejectDispatch: ((reason?: unknown) => void) | null = null;
    const dispatch = vi.fn(() => new Promise<void>((_resolve, reject) => {
      rejectDispatch = reject;
    })) as Dispatch;
    const bounds = (left: number, top: number, width: number, height: number) => ({
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
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains("reference-plane")) return bounds(0, 0, 402, 874);
      if (this.getAttribute("data-food") === "berry-bowl") return bounds(88, 692, 76, 94);
      return bounds(0, 0, 0, 0);
    });

    const { container } = render(
      <div className="reference-plane">
        <FeedModeLayer
          dashboard={dashboardSnapshot()}
          reducedMotion={false}
          feedback={vi.fn()}
          dispatch={dispatch}
        />
      </div>,
    );
    const berry = screen.getByRole("button", { name: "Ягодная миска" });
    fireEvent.pointerDown(berry, { pointerId: 1, button: 0, clientX: 126, clientY: 739 });
    fireEvent.pointerMove(berry, { pointerId: 1, clientX: 200, clientY: 400 });
    fireEvent.pointerUp(berry, { pointerId: 1, clientX: 200, clientY: 400 });

    expect(dispatch).toHaveBeenCalledWith("FEED_CONSUME", { food: "berry-bowl" });
    expect(berry).toHaveAttribute("data-phase", "idle");
    expect(container.querySelector(".feed-pulse")).not.toBeInTheDocument();
    expect(berry.style.getPropertyValue("--food-offset-x")).not.toBe("0px");

    act(() => rejectDispatch?.(new Error("LOCAL_DATA_ERROR")));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Питомец поел, но не смог ответить. Попробуйте ещё раз.",
    );
    await waitFor(() => {
      expect(berry.style.getPropertyValue("--food-offset-x")).toBe("0px");
      expect(berry.style.getPropertyValue("--food-offset-y")).toBe("0px");
    });
    expect(berry).toHaveAttribute("data-phase", "idle");
    expect(container.querySelector(".feed-pulse")).not.toBeInTheDocument();
  });

  it("does not duplicate a pointer feed activation and preserves keyboard activation", async () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const feedback = vi.fn();
    const bounds = (left: number, top: number, width: number, height: number) => ({
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
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains("reference-plane")) return bounds(0, 0, 402, 874);
      if (this.getAttribute("data-food") === "berry-bowl") return bounds(88, 692, 76, 94);
      return bounds(0, 0, 0, 0);
    });
    render(
      <div className="reference-plane">
        <FeedModeLayer
          dashboard={dashboardSnapshot()}
          reducedMotion={false}
          feedback={feedback}
          dispatch={dispatch}
        />
      </div>,
    );
    const berry = screen.getByRole("button", { name: "Ягодная миска" });

    fireEvent.pointerDown(berry, { pointerId: 1, button: 0, clientX: 126, clientY: 739 });
    fireEvent.pointerUp(berry, { pointerId: 1, clientX: 126, clientY: 739 });
    fireEvent.click(berry, { detail: 1 });

    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(1));
    expect(feedback).toHaveBeenCalledTimes(1);

    berry.focus();
    fireEvent.click(berry, { detail: 0 });

    await waitFor(() => expect(dispatch).toHaveBeenCalledTimes(2));
    expect(feedback).toHaveBeenCalledTimes(2);
    expect(berry).toHaveFocus();
  });
});

describe("dashboard chat presentation", () => {
  it("keeps chat input usable while active and retains only the trimmed latest submissions", () => {
    const dispatchMock = vi.fn(async (_type: ProductCommandType, _payload?: JsonValue) => undefined);
    const dispatch = dispatchMock as Dispatch;
    const feedback = vi.fn();
    render(
      <ConversationInput
        mode="chat"
        initialValue=""
        error={null}
        busy
        feedback={feedback}
        dispatch={dispatch}
      />,
    );
    const input = screen.getByPlaceholderText("Расскажи о себе");

    fireEvent.change(input, { target: { value: "   первое сообщение   " } });
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
    fireEvent.change(input, { target: { value: "я".repeat(1_010) } });
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));

    expect(input).not.toBeDisabled();
    expect(dispatch).toHaveBeenNthCalledWith(1, "CHAT_SEND", { message: "первое сообщение" });
    const secondPayload = vi.mocked(dispatch).mock.calls[1]?.[1] as { message: string };
    expect(secondPayload.message).toHaveLength(1_000);
    expect(feedback).toHaveBeenNthCalledWith(1, "chatSubmit");
    expect(feedback).toHaveBeenNthCalledWith(2, "chatSubmit");
  });

  it("restores a chat submission when dispatch fails before native confirmation", async () => {
    const dispatch = vi.fn().mockRejectedValue(new Error("temporary")) as Dispatch;
    render(
      <ConversationInput
        mode="chat"
        initialValue=""
        error={null}
        busy={false}
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    const input = screen.getByPlaceholderText("Расскажи о себе");
    fireEvent.change(input, { target: { value: "Не потеряй меня" } });
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));

    await waitFor(() => expect(input).toHaveValue("Не потеряй меня"));
    expect(dispatch).toHaveBeenCalledTimes(1);
  });

  it("retries the durable chat identity with empty local input and fences duplicate clicks", async () => {
    let resolveRetry: (() => void) | undefined;
    const dispatch = vi.fn(() => new Promise<void>((resolve) => {
      resolveRetry = resolve;
    })) as Dispatch;
    render(
      <ConversationInput
        mode="chat"
        initialValue=""
        error="Не получилось отправить сообщение. Попробуйте ещё раз."
        busy={false}
        thinking={false}
        retryable
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );

    const input = screen.getByPlaceholderText("Расскажи о себе");
    const retry = screen.getByRole("button", { name: "Повторить отправку" });
    const error = screen.getByRole("alert");
    expect(input).toBeDisabled();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAttribute("aria-describedby", "chat-input-error");
    expect(error).toHaveAttribute("id", "chat-input-error");
    expect(error).toHaveTextContent("Не получилось отправить сообщение. Попробуйте ещё раз.");
    expect(retry).toBeEnabled();

    fireEvent.click(retry);
    fireEvent.click(retry);

    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(dispatch).toHaveBeenCalledWith("CHAT_RETRY", undefined);
    expect(dispatch).not.toHaveBeenCalledWith("CHAT_SEND", expect.anything());
    expect(retry).toBeDisabled();

    resolveRetry?.();
    await waitFor(() => expect(retry).toBeEnabled());
  });

  it("shows active thinking and the latest queued request without restarting reduced motion", () => {
    vi.useFakeTimers();
    const { rerender } = render(<ThinkingIndicator mode="chat" reducedMotion={false} queued />);
    const status = screen.getByRole("status", { name: /Следующее сообщение в очереди/ });
    expect(document.querySelector(".thinking-indicator__queued")).not.toBeInTheDocument();
    expect(status).toHaveAttribute("data-frame", "1");
    act(() => vi.advanceTimersByTime(200));
    expect(status).toHaveAttribute("data-frame", "2");

    rerender(<ThinkingIndicator mode="chat" reducedMotion queued />);
    expect(status).toHaveAttribute("data-frame", "1");
    act(() => vi.advanceTimersByTime(1_000));
    expect(status).toHaveAttribute("data-frame", "1");
  });

  it("measures visual wrapping from scrollHeight and clamps the native composer height", () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const lineCount = vi.fn();
    vi.spyOn(HTMLTextAreaElement.prototype, "scrollHeight", "get").mockImplementation(function (
      this: HTMLTextAreaElement,
    ) {
      return this.value.length > 20 ? 160 : 62;
    });
    render(
      <ConversationInput
        mode="chat"
        initialValue=""
        error={null}
        busy={false}
        feedback={vi.fn()}
        dispatch={dispatch}
        onLineCountChange={lineCount}
      />,
    );
    lineCount.mockClear();
    const input = screen.getByPlaceholderText("Расскажи о себе");

    fireEvent.change(input, { target: { value: "Длинная строка без ручных переносов, которая визуально переносится" } });

    expect(input).toHaveStyle({ height: "134px" });
    expect(lineCount).toHaveBeenLastCalledWith(4);
  });
});

describe("dashboard outfit and travel operations", () => {
  it("submits the projected outfit draft with the DTO price and dismisses focus", () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const feedback = vi.fn();
    render(
      <ConversationInput
        mode="outfit"
        initialValue="  красный шарф  "
        error={null}
        busy={false}
        experienceCost={350}
        feedback={feedback}
        dispatch={dispatch}
      />,
    );
    const input = screen.getByPlaceholderText("В футболку Metallica");
    expect(input).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Создать наряд за 350 монет" }));

    expect(dispatch).toHaveBeenCalledWith("OUTFIT_SUBMIT", { prompt: "красный шарф" });
    expect(feedback).toHaveBeenCalledWith("buttonPress");
    expect(input).toHaveValue("  красный шарф  ");
    expect(input).not.toHaveFocus();
  });

  it("retries only the durable retryable identity and exposes its original prompt", () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    render(
      <ConversationInput
        mode="outfit"
        initialValue=""
        error={null}
        busy={false}
        pendingStatus="retryable"
        pendingLabel="красный шарф"
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );

    expect(screen.getByText(
      "Не получилось нарядить персонажа. Попробуйте ещё раз. · красный шарф",
    )).toBeInTheDocument();
    expect(screen.getByPlaceholderText("В футболку Metallica")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Повторить создание наряда" }));

    expect(dispatch).toHaveBeenCalledWith("OUTFIT_RETRY", undefined);
    expect(dispatch).not.toHaveBeenCalledWith("OUTFIT_SUBMIT", expect.anything());
  });

  it("does not retry an auto-recovering pending travel, but retries a definite safe local failure", () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    const { rerender } = render(
      <ConversationInput
        mode="travel"
        initialValue=""
        error={null}
        busy={false}
        thinking={false}
        pendingStatus="pending"
        pendingLabel="ночной рынок духов"
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    expect(screen.getByText("Путешествие готовится: ночной рынок духов")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Отправить в путешествие" })).toBeDisabled();

    rerender(
      <ConversationInput
        mode="travel"
        initialValue="новое место"
        error={null}
        busy={false}
        thinking={false}
        pendingStatus="outcomeUnknown"
        pendingLabel="ночной рынок духов"
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    expect(screen.getByText("Проверяю статус запроса: ночной рынок духов")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Отправить в путешествие" })).toBeDisabled();

    rerender(
      <ConversationInput
        mode="travel"
        initialValue=""
        error="Не получилось отправиться в путешествие. Попробуйте ещё раз."
        busy={false}
        thinking={false}
        pendingStatus="pending"
        pendingLabel="ночной рынок духов"
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "Не получилось отправиться в путешествие. Попробуйте ещё раз. · ночной рынок духов",
    );
    expect(screen.getByPlaceholderText("На ночной рынок духов")).toHaveAttribute(
      "aria-describedby",
      "travel-operation-status",
    );
    fireEvent.click(screen.getByRole("button", { name: "Повторить путешествие" }));

    expect(dispatch).toHaveBeenCalledWith("TRAVEL_RETRY", undefined);
  });

  it("allows a fresh submit after a terminal travel failure", () => {
    const dispatch = vi.fn(async () => undefined) as Dispatch;
    render(
      <ConversationInput
        mode="travel"
        initialValue="новое место"
        error={null}
        busy={false}
        pendingStatus="failed"
        pendingLabel="старое место"
        feedback={vi.fn()}
        dispatch={dispatch}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Отправить в путешествие" }));

    expect(dispatch).toHaveBeenCalledWith("TRAVEL_SUBMIT", { prompt: "новое место" });
  });
});

function ReplyHarness({
  reply,
  session,
  dispatch,
  active = true,
}: {
  reply: DashboardReplySnapshot | null;
  session: FirstSessionSnapshot | null;
  dispatch: Dispatch;
  active?: boolean;
}) {
  const presentation = useDashboardReplyPresentation(reply, session, false, dispatch, active);
  return presentation.message ? (
    <AnimatedDialogueText
      message={presentation.message}
      reducedMotion={false}
      canAdvance={presentation.canAdvance}
      onRevealComplete={presentation.markRevealComplete}
      onAdvance={presentation.advance}
      active={active}
    />
  ) : null;
}

function ReducedReplyHarness({
  reply,
  session,
  dispatch,
}: {
  reply: DashboardReplySnapshot | null;
  session: FirstSessionSnapshot | null;
  dispatch: Dispatch;
}) {
  const presentation = useDashboardReplyPresentation(reply, session, true, dispatch);
  return presentation.message ? <div>{presentation.message}</div> : null;
}

describe("dashboard reply lifecycle", () => {
  it("defers every reply effect while inactive and resumes each effect once", async () => {
    vi.useFakeTimers();
    const dispatchMock = vi.fn(async (_type: ProductCommandType, _payload?: JsonValue) => undefined);
    const dispatch = dispatchMock as Dispatch;
    const reply: DashboardReplySnapshot = {
      source: "chat",
      requestKey: "chat-hidden",
      portions: ["Первая", "Вторая"],
      portionIndex: 0,
      hasNextPortion: true,
      autoAdvanceDelayMillis: 6_000,
    };
    const { rerender } = render(
      <ReplyHarness reply={reply} session={firstSession} dispatch={dispatch} active={false} />,
    );

    act(() => vi.advanceTimersByTime(60_000));
    expect(dispatchMock).not.toHaveBeenCalled();

    rerender(<ReplyHarness reply={reply} session={firstSession} dispatch={dispatch} />);
    expect(dispatchMock.mock.calls.filter(([type]) => type === "CHAT_REPLY_PRESENTED"))
      .toHaveLength(1);
    act(() => vi.advanceTimersByTime(5_999));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE"))
      .toHaveLength(0);
    act(() => vi.advanceTimersByTime(1));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE"))
      .toHaveLength(1);
    await act(async () => Promise.resolve());
    rerender(<ReplyHarness reply={{ ...reply }} session={firstSession} dispatch={dispatch} />);
    act(() => vi.advanceTimersByTime(60_000));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "CHAT_REPLY_PRESENTED"))
      .toHaveLength(1);
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE"))
      .toHaveLength(1);
  });

  it("presents chat once, cancels stale auto-advance, and completes the final onboarding portion", async () => {
    vi.useFakeTimers();
    const dispatchMock = vi.fn(async (_type: ProductCommandType, _payload?: JsonValue) => undefined);
    const dispatch = dispatchMock as Dispatch;
    const firstReply: DashboardReplySnapshot = {
      source: "chat",
      requestKey: "chat-1",
      portions: ["Первая", "Вторая"],
      portionIndex: 0,
      hasNextPortion: true,
      autoAdvanceDelayMillis: 6_000,
    };
    const replacement: DashboardReplySnapshot = {
      ...firstReply,
      requestKey: "chat-2",
      portions: ["Новая", "Финальная"],
    };
    const { rerender } = render(
      <ReplyHarness reply={firstReply} session={firstSession} dispatch={dispatch} />,
    );
    expect(dispatch).toHaveBeenCalledWith("CHAT_REPLY_PRESENTED", { requestKey: "chat-1" });

    act(() => vi.advanceTimersByTime(3_000));
    rerender(<ReplyHarness reply={replacement} session={firstSession} dispatch={dispatch} />);
    act(() => vi.advanceTimersByTime(3_000));
    expect(dispatch).not.toHaveBeenCalledWith("REPLY_ADVANCE", { requestKey: "chat-1" });

    act(() => vi.advanceTimersByTime(3_000));
    expect(dispatch).toHaveBeenCalledWith("REPLY_ADVANCE", { requestKey: "chat-2" });

    rerender(
      <ReplyHarness
        reply={{ ...replacement, portionIndex: 1, hasNextPortion: false }}
        session={firstSession}
        dispatch={dispatch}
      />,
    );
    await act(async () => vi.advanceTimersByTime(2_000));
    expect(dispatch).toHaveBeenCalledWith("REPLY_COMPLETE", { requestKey: "chat-2" });
    expect(dispatchMock.mock.calls.filter(([type]) => type === "CHAT_REPLY_PRESENTED")).toHaveLength(2);
  });

  it("retries a failed presented acknowledgement once and marks only its success", async () => {
    let presentedAttempts = 0;
    const dispatchMock = vi.fn(async (type: ProductCommandType) => {
      if (type === "CHAT_REPLY_PRESENTED" && presentedAttempts++ === 0) {
        throw new Error("temporary");
      }
    });
    const dispatch = dispatchMock as Dispatch;
    const reply: DashboardReplySnapshot = {
      source: "chat",
      requestKey: "chat-presented-retry",
      portions: ["Первая", "Вторая"],
      portionIndex: 0,
      hasNextPortion: true,
      autoAdvanceDelayMillis: 60_000,
    };
    const { rerender } = render(
      <ReplyHarness reply={reply} session={firstSession} dispatch={dispatch} />,
    );

    await waitFor(() => {
      expect(dispatchMock.mock.calls.filter(([type]) => type === "CHAT_REPLY_PRESENTED"))
        .toHaveLength(2);
    });
    rerender(<ReplyHarness reply={{ ...reply }} session={firstSession} dispatch={dispatch} />);
    await act(async () => Promise.resolve());
    expect(dispatchMock.mock.calls.filter(([type]) => type === "CHAT_REPLY_PRESENTED"))
      .toHaveLength(2);
  });

  it("reschedules one failed auto-advance using the original delay", async () => {
    vi.useFakeTimers();
    let advanceAttempts = 0;
    const dispatchMock = vi.fn(async (type: ProductCommandType) => {
      if (type === "REPLY_ADVANCE" && advanceAttempts++ === 0) throw new Error("temporary");
    });
    const reply: DashboardReplySnapshot = {
      source: "feed",
      requestKey: "feed-advance-retry",
      portions: ["Первая", "Вторая"],
      portionIndex: 0,
      hasNextPortion: true,
      autoAdvanceDelayMillis: 2_000,
    };
    render(<ReplyHarness reply={reply} session={firstSession} dispatch={dispatchMock as Dispatch} />);

    await act(async () => vi.advanceTimersByTime(2_000));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE")).toHaveLength(1);
    await act(async () => vi.advanceTimersByTime(1_999));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE")).toHaveLength(1);
    await act(async () => vi.advanceTimersByTime(1));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE")).toHaveLength(2);
    await act(async () => vi.advanceTimersByTime(10_000));
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_ADVANCE")).toHaveLength(2);
  });

  it("retries a failed final completion once and does not repeat after confirmation", async () => {
    let completionAttempts = 0;
    const dispatchMock = vi.fn(async (type: ProductCommandType) => {
      if (type === "REPLY_COMPLETE" && completionAttempts++ === 0) throw new Error("temporary");
    });
    const dispatch = dispatchMock as Dispatch;
    const reply: DashboardReplySnapshot = {
      source: "feed",
      requestKey: "feed-complete-retry",
      portions: ["Готово"],
      portionIndex: 0,
      hasNextPortion: false,
      autoAdvanceDelayMillis: 0,
    };
    const { rerender } = render(
      <ReducedReplyHarness reply={reply} session={firstSession} dispatch={dispatch} />,
    );

    await waitFor(() => {
      expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_COMPLETE"))
        .toHaveLength(2);
    });
    rerender(
      <ReducedReplyHarness reply={{ ...reply }} session={firstSession} dispatch={dispatch} />,
    );
    await act(async () => Promise.resolve());
    expect(dispatchMock.mock.calls.filter(([type]) => type === "REPLY_COMPLETE"))
      .toHaveLength(2);
  });
});
