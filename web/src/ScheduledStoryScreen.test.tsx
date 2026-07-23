import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ScheduledStoryScreen } from "./ScheduledStoryScreen";
import {
  storyOutcomeFixture,
  storyScreenFixture,
} from "./test/eventStoryFixtures";

function storyProps(
  overrides: Partial<Parameters<typeof ScheduledStoryScreen>[0]> = {},
): Parameters<typeof ScheduledStoryScreen>[0] {
  return {
    state: storyScreenFixture(),
    reducedMotion: false,
    onChoice: vi.fn(),
    onFinish: vi.fn(),
    onBack: vi.fn(),
    ...overrides,
  };
}

function storyParagraph(text: string, root: HTMLElement = document.body): HTMLElement {
  const accessibleText = within(root).getByText(text, {
    selector: ".scheduled-story__paragraph > .sr-only",
  });
  const paragraph = accessibleText.closest<HTMLElement>(".scheduled-story__paragraph");
  if (!paragraph) throw new Error(`Story paragraph missing for: ${text}`);
  return paragraph;
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("Android scheduled Story presentation", () => {
  it("renders question media, glyph timing, alternating glass answers, and callbacks", () => {
    vi.useFakeTimers();
    const onChoice = vi.fn();
    const onBack = vi.fn();
    const state = storyScreenFixture();
    const { container } = render(
      <ScheduledStoryScreen {...storyProps({ state, onChoice, onBack })} />,
    );

    expect(screen.getByRole("img", {
      name: "Видео события: Шорох у старого дерева",
    })).toBeInTheDocument();
    expect(container.querySelector('video[src="/assets/media/onboarding-bat-situation.mp4"]')).not.toBeNull();
    expect(screen.getByLabelText("История и вопрос")).toBeInTheDocument();
    const storyRegion = screen.getByRole("region", { name: "Интерактивное путешествие" });
    expect(storyRegion).toHaveAttribute("tabindex", "0");
    storyRegion.focus();
    expect(storyRegion).toHaveFocus();

    const storyCopy = storyParagraph(state.story.text);
    const questionParagraph = storyParagraph(state.story.question);
    const firstStoryGlyph = storyCopy.querySelector<HTMLElement>(".scheduled-story__glyph");
    const firstQuestionGlyph = questionParagraph.querySelector<HTMLElement>(".scheduled-story__glyph");
    expect(firstStoryGlyph?.style.getPropertyValue("--story-glyph-delay")).toBe("0ms");
    expect(firstStoryGlyph?.style.getPropertyValue("--story-glyph-duration")).toBe("");
    expect(firstQuestionGlyph).toHaveAttribute("data-glyph-index", String(state.story.text.length));
    expect(firstQuestionGlyph?.style.getPropertyValue("--story-glyph-delay")).toBe(
      `${state.story.text.length * 12}ms`,
    );

    const answers = within(screen.getByLabelText("Варианты ответа")).getAllByRole("button");
    expect(answers).toHaveLength(4);
    const firstShell = answers[0].closest<HTMLElement>(".scheduled-story__glass-answer-shell");
    const secondShell = answers[1].closest<HTMLElement>(".scheduled-story__glass-answer-shell");
    expect(firstShell?.style.getPropertyValue("--story-answer-tilt")).toBe("-2deg");
    expect(secondShell?.style.getPropertyValue("--story-answer-tilt")).toBe("2deg");
    expect(secondShell?.style.getPropertyValue("--story-answer-delay")).toBe("200ms");

    fireEvent.pointerDown(answers[0], { button: 0 });
    expect(answers[0]).toHaveAttribute("data-press-phase", "pressing");
    fireEvent.pointerUp(answers[0], { button: 0 });
    expect(answers[0]).toHaveAttribute("data-press-phase", "releasing");
    act(() => vi.advanceTimersByTime(140));
    expect(answers[0]).toHaveAttribute("data-press-phase", "idle");
    fireEvent.click(answers[0]);
    expect(onChoice).toHaveBeenCalledWith("Подойти");

    fireEvent.click(screen.getByRole("button", { name: "Назад" }));
    expect(onBack).toHaveBeenCalledOnce();
  });

  it("allows only the durable pending choice in retryable state and routes it to retry", () => {
    const state = storyScreenFixture({
      phase: "retryable",
      durableRequestKey: "choice-fixture",
      pendingChoice: "Подойти",
      error: "Не получилось продолжить историю. Попробуй ещё раз.",
    });
    const onChoice = vi.fn();
    const onRetry = vi.fn();
    render(
      <ScheduledStoryScreen {...storyProps({ state, onChoice, onRetry })} />,
    );

    expect(screen.getByRole("button", { name: "Подойти" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Позвать" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Спрятаться" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Подождать" })).toBeDisabled();
    expect(screen.getByRole("status", {
      name: "Не получилось продолжить историю. Попробуй ещё раз.",
    })).toHaveTextContent("Не получилось продолжить историю. Попробуй ещё раз.");
    fireEvent.click(screen.getByRole("button", { name: "Подойти" }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(onChoice).not.toHaveBeenCalled();
  });

  it("does not expose an error outside the retryable phase", () => {
    const onChoice = vi.fn();
    const message = "Не получилось продолжить историю. Попробуй ещё раз.";
    render(
      <ScheduledStoryScreen
        {...storyProps({
          state: storyScreenFixture({ phase: "question", error: message }),
          onChoice,
        })}
      />,
    );

    expect(screen.queryByRole("status", { name: message })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Подойти" }));
    expect(onChoice).toHaveBeenCalledWith("Подойти");
  });

  it("renders the result sequence, success media, reward semantics, and Finish", () => {
    const result = storyOutcomeFixture();
    const onFinish = vi.fn();
    const state = storyScreenFixture({ phase: "result", result });
    const { container } = render(
      <ScheduledStoryScreen {...storyProps({ state, onFinish })} />,
    );

    expect(screen.getByRole("img", {
      name: "Итог события: Шорох у старого дерева",
    })).toBeInTheDocument();
    expect(container.querySelector('video[src="/assets/media/onboarding-bat-success.mp4"]')).not.toBeNull();
    const copy = screen.getByLabelText("Результат выбора");
    expect(storyParagraph(result.text, copy)).toBeInTheDocument();
    expect(storyParagraph(result.consequence, copy)).toBeInTheDocument();
    expect(storyParagraph(result.reaction, copy)).toHaveClass(
      "scheduled-story__paragraph--muted",
    );
    expect(within(copy).getByLabelText("Получено 125 единиц опыта")).toHaveTextContent("+125");
    expect(screen.queryByLabelText("Варианты ответа")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Завершить" }));
    expect(onFinish).toHaveBeenCalledOnce();
  });

  it("freezes motion to bundled posters and immediate glyphs", () => {
    const state = storyScreenFixture({
      kind: "onboardingBat",
      origin: "dashboard",
      story: {
        ...storyScreenFixture().story,
        storyId: "onboarding-bat-help-v1-pet-a",
        imageRef: null,
        videoRef: null,
      },
    });
    const { container } = render(
      <ScheduledStoryScreen {...storyProps({ state, reducedMotion: true })} />,
    );

    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector('img[src="/res/onboarding_bat_situation.png"]')).not.toBeNull();
    expect(container.querySelectorAll(".scheduled-story__paragraph--reduced").length).toBe(2);
  });

  it("honors forcePoster even when motion is allowed", () => {
    const { container } = render(
      <ScheduledStoryScreen {...storyProps({ forcePoster: true })} />,
    );

    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector('img[src="/res/onboarding_bat_situation.png"]')).not.toBeNull();
  });

  it("unmounts video while the Android lifecycle is backgrounded", () => {
    const { container, rerender } = render(
      <ScheduledStoryScreen {...storyProps({ foreground: true })} />,
    );
    expect(container.querySelector("video")).not.toBeNull();

    rerender(<ScheduledStoryScreen {...storyProps({ foreground: false })} />);
    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector('img[src="/res/onboarding_bat_situation.png"]')).not.toBeNull();
  });

  it("accepts only opaque same-origin media capabilities for remote Story media", () => {
    const imageRef = `/media/v1/${"a".repeat(32)}/${"1".repeat(32)}`;
    const videoRef = `/media/v1/${"a".repeat(32)}/${"2".repeat(32)}`;
    const base = storyScreenFixture();
    const state = storyScreenFixture({
      story: { ...base.story, imageRef, videoRef },
    });
    const { container } = render(
      <ScheduledStoryScreen {...storyProps({ state })} />,
    );

    expect(container.querySelector(`.scheduled-story__media img[src="${imageRef}"]`)).not.toBeNull();
    expect(container.querySelector(`video[src="${videoRef}"]`)).not.toBeNull();
    expect(container.querySelector(
      '.scheduled-story__backdrop img[src="/res/onboarding_bat_situation.png"]',
    )).not.toBeNull();
  });

  it("hides Story content immediately and cycles the exact native pending indicator", () => {
    vi.useFakeTimers();
    const { container, rerender } = render(
      <ScheduledStoryScreen {...storyProps({ choicePending: true })} />,
    );

    const scroll = container.querySelector(".scheduled-story__scroll");
    expect(scroll).toHaveClass("scheduled-story__scroll--pending");
    expect(scroll).toHaveAttribute("aria-hidden", "true");
    const thinking = screen.getByRole("status", { name: "Персонаж думает" });
    expect(thinking).toHaveAttribute("data-frame", "1");
    expect(thinking.querySelector("img")).toHaveAttribute("src", "/res/thinking_frame_1.png");
    expect(screen.getByRole("button", { name: "Назад" })).toBeEnabled();

    act(() => vi.advanceTimersByTime(200));
    expect(thinking).toHaveAttribute("data-frame", "2");
    act(() => vi.advanceTimersByTime(400));
    expect(thinking).toHaveAttribute("data-frame", "1");

    rerender(<ScheduledStoryScreen {...storyProps({ choicePending: true, reducedMotion: true })} />);
    act(() => vi.advanceTimersByTime(600));
    expect(screen.getByRole("status", { name: "Персонаж думает" })).toHaveAttribute(
      "data-frame",
      "1",
    );
  });

  it("matches bat choice restrictions and exact five-paragraph question pacing", () => {
    const onChoice = vi.fn();
    const base = storyScreenFixture();
    const story = {
      ...base.story,
      storyId: "onboarding-bat-help-v1-pet-a",
      title: "Малыш летучей мыши",
      question: "К какой группе относится летучая мышь?",
      choices: ["Птицы", "Млекопитающие", "Насекомые", "Пресмыкающиеся"],
      enabledChoice: "Млекопитающие",
      questionParagraphs: [
        "Ой, что это?",
        "Под крышей пищит детёныш летучей мыши.",
        "Его мама рядом и хочет его накормить.",
        "Чтобы понять, чем она кормит малыша, нужно узнать, к какой группе относится летучая мышь.",
        "К какой группе относится летучая мышь?",
      ],
      imageRef: null,
      videoRef: null,
    };
    const { container } = render(
      <ScheduledStoryScreen
        {...storyProps({
          state: storyScreenFixture({ kind: "onboardingBat", origin: "dashboard", story }),
          reducedMotion: true,
          onChoice,
        })}
      />,
    );

    expect(storyParagraph("Ой, что это?")).toBeInTheDocument();
    expect(storyParagraph("Под крышей пищит детёныш летучей мыши.")).toBeInTheDocument();
    expect(storyParagraph("Его мама рядом и хочет его накормить.")).toBeInTheDocument();
    expect(storyParagraph(
      "Чтобы понять, чем она кормит малыша, нужно узнать, к какой группе относится летучая мышь.",
    )).toBeInTheDocument();
    expect(storyParagraph("К какой группе относится летучая мышь?")).toBeInTheDocument();
    expect(container.querySelectorAll(".scheduled-story__copy > .scheduled-story__paragraph")).toHaveLength(5);

    expect(screen.getByRole("button", { name: "Птицы" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Насекомые" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Пресмыкающиеся" })).toBeDisabled();
    const correct = screen.getByRole("button", { name: "Млекопитающие" });
    expect(correct).toBeEnabled();
    fireEvent.click(correct);
    expect(onChoice).toHaveBeenCalledWith("Млекопитающие");
    expect(container.querySelectorAll('img[src="/res/onboarding_bat_situation.png"]')).toHaveLength(2);
  });

  it("uses the exact two bat result paragraphs before reaction and reward", () => {
    const base = storyScreenFixture();
    const result = storyOutcomeFixture({
      paragraphs: [
        "Летучая мышь относится к млекопитающим.",
        "Мама добралась до малыша, согрела его и накормила молоком.",
      ],
    });
    const story = {
      ...base.story,
      storyId: "onboarding-bat-help-v1-pet-a",
      enabledChoice: "Млекопитающие",
      imageRef: null,
      videoRef: null,
    };
    const { container } = render(
      <ScheduledStoryScreen
        {...storyProps({
          state: storyScreenFixture({
            phase: "result",
            kind: "onboardingBat",
            origin: "dashboard",
            durableRequestKey: result.requestKey,
            story,
            result,
          }),
          reducedMotion: true,
        })}
      />,
    );

    expect(storyParagraph("Летучая мышь относится к млекопитающим.")).toBeInTheDocument();
    expect(storyParagraph(
      "Мама добралась до малыша, согрела его и накормила молоком.",
    )).toBeInTheDocument();
    expect(screen.queryByText(result.consequence, {
      selector: ".scheduled-story__paragraph > .sr-only",
    })).not.toBeInTheDocument();
    expect(container.querySelectorAll(".scheduled-story__copy > .scheduled-story__paragraph")).toHaveLength(2);
  });
});
