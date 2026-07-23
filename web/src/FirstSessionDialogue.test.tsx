import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { FirstSessionSnapshot } from "./contracts";
import { AnimatedDialogueText, useFirstSessionPresentation } from "./FirstSessionDialogue";

const speech = vi.hoisted(() => {
  const start = vi.fn();
  const stop = vi.fn();
  return {
    start,
    stop,
    create: vi.fn(() => ({ start, stop })),
  };
});

vi.mock("./speechSequence", () => ({
  createBrowserSpeechSequence: speech.create,
}));

function Harness({ session, reducedMotion = false, active = true }: {
  session: FirstSessionSnapshot;
  reducedMotion?: boolean;
  active?: boolean;
}) {
  const presentation = useFirstSessionPresentation(
    session,
    "Запасной текст",
    reducedMotion,
    active,
  );
  return (
    <>
      <AnimatedDialogueText
        message={presentation.message}
        reducedMotion={reducedMotion}
        canAdvance={presentation.canAdvance}
        onRevealComplete={presentation.markRevealComplete}
        onAdvance={presentation.advance}
        active={active}
      />
      {presentation.actionVisible ? <button type="button">Продолжить</button> : null}
    </>
  );
}

const session: FirstSessionSnapshot = {
  stage: "awaiting-chat",
  allowedAction: "chat",
  messagePortions: ["Привет", "Расскажи о себе"],
  selectedDestination: null,
};

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("first-session dialogue", () => {
  it("finishes a six-glyph reveal at exactly 820 ms", () => {
    vi.useFakeTimers();
    render(
      <Harness session={{ ...session, messagePortions: ["Привет"] }} />,
    );

    act(() => vi.advanceTimersByTime(819));
    expect(screen.queryByRole("button", { name: "Продолжить" })).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByRole("button", { name: "Продолжить" })).toBeInTheDocument();
  });

  it("allows an early tap to cancel the reveal wait and show the next portion", () => {
    vi.useFakeTimers();
    render(<Harness session={session} />);
    const dialogue = screen.getByRole("button", { name: "Привет" });

    fireEvent.click(dialogue);
    expect(screen.getByRole("status")).toHaveTextContent("Расскажи о себе");
    expect(screen.queryByRole("button", { name: "Расскажи о себе" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Продолжить" })).not.toBeInTheDocument();

    act(() => vi.advanceTimersByTime(1_200));
    expect(screen.getByRole("button", { name: "Продолжить" })).toBeInTheDocument();
  });

  it("shows the final action without motion when reduced motion is enabled", () => {
    vi.useFakeTimers();
    render(
      <Harness
        session={{ ...session, messagePortions: ["Привет"] }}
        reducedMotion
      />,
    );
    act(() => vi.runOnlyPendingTimers());
    expect(screen.getByRole("button", { name: "Продолжить" })).toBeInTheDocument();
  });

  it("pauses reveal and speech while hidden, then resumes the exact remaining duration", () => {
    vi.useFakeTimers();
    const onRevealComplete = vi.fn();
    const onAdvance = vi.fn();
    const { rerender } = render(
      <AnimatedDialogueText
        message="Привет"
        reducedMotion={false}
        canAdvance
        onRevealComplete={onRevealComplete}
        onAdvance={onAdvance}
        active={false}
      />,
    );

    expect(speech.create).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(5_000));
    expect(onRevealComplete).not.toHaveBeenCalled();
    expect(speech.start).not.toHaveBeenCalled();

    rerender(
      <AnimatedDialogueText
        message="Привет"
        reducedMotion={false}
        canAdvance
        onRevealComplete={onRevealComplete}
        onAdvance={onAdvance}
        active
      />,
    );
    expect(speech.create).toHaveBeenCalledTimes(1);
    expect(speech.start).toHaveBeenLastCalledWith(820);

    act(() => vi.advanceTimersByTime(300));
    rerender(
      <AnimatedDialogueText
        message="Привет"
        reducedMotion={false}
        canAdvance
        onRevealComplete={onRevealComplete}
        onAdvance={onAdvance}
        active={false}
      />,
    );
    expect(speech.stop).toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(5_000));
    expect(onRevealComplete).not.toHaveBeenCalled();
    expect(speech.start).toHaveBeenCalledTimes(1);

    rerender(
      <AnimatedDialogueText
        message="Привет"
        reducedMotion={false}
        canAdvance
        onRevealComplete={onRevealComplete}
        onAdvance={onAdvance}
        active
      />,
    );
    expect(speech.start).toHaveBeenLastCalledWith(520);
    act(() => vi.advanceTimersByTime(519));
    expect(onRevealComplete).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(onRevealComplete).toHaveBeenCalledTimes(1);
  });

  it("does not advance a first-session portion through a hidden synthetic click", () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness session={session} active={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Привет" }));
    expect(screen.getByRole("button", { name: "Привет" })).toBeInTheDocument();

    rerender(<Harness session={session} active />);
    fireEvent.click(screen.getByRole("button", { name: "Привет" }));
    expect(screen.getByRole("status")).toHaveTextContent("Расскажи о себе");
  });

  it("keeps only a real multi-portion advance in the keyboard button order", () => {
    render(<Harness session={session} reducedMotion />);
    const advance = screen.getByRole("button", { name: "Привет" });
    advance.focus();

    fireEvent.keyDown(advance, { key: "Enter" });
    fireEvent.click(advance, { detail: 0 });
    fireEvent.keyUp(advance, { key: "Enter" });

    expect(screen.getByRole("status")).toHaveTextContent("Расскажи о себе");
    expect(screen.queryByRole("button", { name: "Расскажи о себе" })).not.toBeInTheDocument();
  });
});
