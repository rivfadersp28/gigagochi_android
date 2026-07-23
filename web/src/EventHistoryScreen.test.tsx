import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EventHistoryScreen } from "./EventHistoryScreen";
import type { TravelVideoShareResult } from "./EventStoryTypes";
import {
  localStoryFixture,
  travelVideoFixture,
} from "./test/eventStoryFixtures";

function historyProps(
  overrides: Partial<Parameters<typeof EventHistoryScreen>[0]> = {},
): Parameters<typeof EventHistoryScreen>[0] {
  return {
    stories: [],
    travelVideos: [],
    reducedMotion: true,
    onShare: vi.fn(() => "opened" as const),
    onHelp: vi.fn(),
    onBack: vi.fn(),
    ...overrides,
  };
}

function rect(top: number, bottom: number): DOMRect {
  return {
    x: 0,
    y: top,
    top,
    bottom,
    left: 0,
    right: 402,
    width: 402,
    height: bottom - top,
    toJSON: () => ({}),
  } as DOMRect;
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("Android Events presentation", () => {
  it("renders the native black empty state and routes Back", () => {
    const onBack = vi.fn();
    const { container } = render(<EventHistoryScreen {...historyProps({ onBack })} />);

    const root = container.querySelector(".event-history");
    expect(root).toBeInTheDocument();
    expect(root).toHaveClass("event-history");
    expect(screen.getByRole("heading", { name: "События" })).toBeInTheDocument();
    expect(screen.getByText("Пока событий нет")).toBeInTheDocument();
    const history = screen.getByRole("region", { name: "История событий" });
    expect(history).toHaveAttribute("tabindex", "0");
    history.focus();
    expect(history).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Назад" }));
    expect(onBack).toHaveBeenCalledOnce();
  });

  it("renders travel, unanswered Story, and answered result cards with native actions", () => {
    const onHelp = vi.fn();
    const unanswered = localStoryFixture({
      storyId: "needs-help",
      createdAt: "2026-07-19T12:00:00Z",
      text: "Тото заметил малыша у старого дерева.",
    });
    const answered = localStoryFixture({
      storyId: "answered",
      createdAt: "2026-07-19T11:00:00Z",
      selectedChoice: "Подойти",
      result: {
        text: "Герой помог малышу выбраться.",
        consequence: "Малыш снова рядом с семьёй.",
        reaction: "Спасибо, теперь всё хорошо.",
        experienceGained: 125,
      },
      resultImageRef: "/res/onboarding_bat_success.png",
      resultVideoRef: "/assets/media/onboarding-bat-success.mp4",
    });
    render(
      <EventHistoryScreen
        {...historyProps({
          stories: [answered, unanswered],
          travelVideos: [travelVideoFixture("lighthouse", {
            completedAtEpochMillis: 2_000_000_000_000,
          })],
          onHelp,
        })}
      />,
    );

    expect(screen.getByRole("img", {
      name: "Видео путешествия: Путешествие к маяку",
    })).toBeInTheDocument();
    const shareButton = screen.getByRole("button", { name: "Поделиться видео" });
    expect(shareButton).toHaveTextContent("Показать друзьям");
    expect(shareButton.parentElement).toHaveStyle({ width: "271.328px" });
    expect(screen.getByRole("img", {
      name: "Видео события: Шорох у старого дерева",
    })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Помочь" }));
    expect(onHelp).toHaveBeenCalledWith(unanswered);

    const resultCard = screen.getByRole("img", {
      name: "Итог события: Шорох у старого дерева",
    }).closest("article");
    expect(resultCard).not.toBeNull();
    const result = within(resultCard as HTMLElement);
    expect(result.getByText("Герой помог малышу выбраться.")).toBeInTheDocument();
    expect(result.getByText("Малыш снова рядом с семьёй.")).toBeInTheDocument();
    expect(result.getByText("Спасибо, теперь всё хорошо.")).toHaveClass(
      "event-history__result-reaction",
    );
    expect(result.getByLabelText("Получено 125 монет")).toHaveTextContent("+125");
  });

  it("keeps Share one-shot while native opens the platform sheet", async () => {
    let settle!: (result: TravelVideoShareResult) => void;
    const onShare = vi.fn(() => new Promise<TravelVideoShareResult>((resolve) => {
      settle = resolve;
    }));
    const asset = travelVideoFixture("share-me");
    render(
      <EventHistoryScreen
        {...historyProps({ travelVideos: [asset], onShare })}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Поделиться видео" }));
    const pending = screen.getByRole("button", { name: "Подготовка видео" });
    expect(pending).toBeDisabled();
    expect(pending).toHaveTextContent("Подготавливаю…");
    fireEvent.click(pending);
    expect(onShare).toHaveBeenCalledOnce();
    expect(onShare).toHaveBeenCalledWith(asset);

    await act(async () => settle("opened"));
    expect(screen.getByRole("button", { name: "Поделиться видео" })).toBeEnabled();
    expect(screen.queryByText("Не удалось открыть отправку видео")).not.toBeInTheDocument();
  });

  it("keeps the native Event spring under reduced motion and interrupts without keyframe restart", () => {
    vi.useFakeTimers();
    render(
      <EventHistoryScreen
        {...historyProps({
          reducedMotion: true,
          travelVideos: [travelVideoFixture("spring")],
        })}
      />,
    );
    const button = screen.getByRole("button", { name: "Поделиться видео" });

    fireEvent.pointerDown(button, { button: 0 });
    expect(button).toHaveAttribute("data-press-phase", "pressing");
    expect(button.style.transition).toContain("linear(");
    act(() => vi.advanceTimersByTime(12));
    fireEvent.pointerUp(button, { button: 0 });
    expect(button).toHaveAttribute("data-press-phase", "releasing");
    expect(button.style.transform).toBe("scale(1.000000)");
    expect(button.style.transition).toContain("linear(");
    act(() => vi.advanceTimersByTime(1_000));
    expect(button).toHaveAttribute("data-press-phase", "idle");

    fireEvent.pointerDown(button, { button: 0 });
    act(() => vi.advanceTimersByTime(12));
    fireEvent.pointerCancel(button);
    expect(button).toHaveAttribute("data-press-phase", "releasing");
  });

  it("places initial travel focus below the padded app bar instead of under Back", () => {
    const offsetTop = vi.spyOn(HTMLElement.prototype, "offsetTop", "get");
    offsetTop.mockImplementation(function (this: HTMLElement) {
      return this.dataset.eventKey === "travel:focus" ? 700 : 0;
    });
    vi.spyOn(window, "getComputedStyle").mockReturnValue({
      paddingTop: "82px",
    } as CSSStyleDeclaration);
    const scrollHeight = vi.spyOn(HTMLElement.prototype, "scrollHeight", "get");
    scrollHeight.mockReturnValue(2_000);
    const clientHeight = vi.spyOn(HTMLElement.prototype, "clientHeight", "get");
    clientHeight.mockReturnValue(874);

    render(
      <EventHistoryScreen
        {...historyProps({
          initialFocusTravelRequestKey: "focus",
          travelVideos: [travelVideoFixture("focus")],
        })}
      />,
    );

    expect(screen.getByLabelText("История событий")).toHaveProperty("scrollTop", 618);
  });

  it("autoplays only the visible card closest to center and follows scroll", async () => {
    const positions = new Map<string, [number, number]>([
      ["travel:near", [160, 620]],
      ["travel:far", [700, 1_300]],
    ]);
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (
      this: HTMLElement,
    ) {
      if (this.classList.contains("event-history__scroll")) return rect(0, 874);
      const key = this.dataset.eventKey;
      const bounds = key ? positions.get(key) : null;
      return bounds ? rect(bounds[0], bounds[1]) : rect(0, 0);
    });
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      queueMicrotask(() => callback(0));
      return 1;
    });

    const { container } = render(
      <EventHistoryScreen
        {...historyProps({
          reducedMotion: false,
          travelVideos: [
            travelVideoFixture("near", { completedAtEpochMillis: 200 }),
            travelVideoFixture("far", { completedAtEpochMillis: 100 }),
          ],
        })}
      />,
    );

    const near = container.querySelector('[data-event-key="travel:near"]') as HTMLElement;
    const far = container.querySelector('[data-event-key="travel:far"]') as HTMLElement;
    await waitFor(() => expect(near).toHaveAttribute("data-event-active", "true"));
    expect(near.querySelector("video")).not.toBeNull();
    expect(far.querySelector("video")).toBeNull();

    positions.set("travel:near", [-700, -100]);
    positions.set("travel:far", [150, 650]);
    fireEvent.scroll(screen.getByLabelText("История событий"));

    await waitFor(() => expect(far).toHaveAttribute("data-event-active", "true"));
    expect(near.querySelector("video")).toBeNull();
    expect(far.querySelector("video")).not.toBeNull();
  });

  it("uses posters only when reduced motion is active", () => {
    const { container } = render(
      <EventHistoryScreen
        {...historyProps({
          reducedMotion: true,
          travelVideos: [travelVideoFixture("still")],
        })}
      />,
    );

    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector('img[src="/res/onboarding_bat_situation.png"]')).not.toBeNull();
  });
});
