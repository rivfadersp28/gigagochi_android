import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CreateScreen } from "./CreateScreen";
import type { CreateSnapshot } from "./contracts";

const initial: CreateSnapshot = {
  step: 0,
  title: "Кого хочешь создать?",
  options: ["Ледяного дракона", "Человек-яблоко", "Водяной дух"],
  nextQuestion: {
    title: "Как его будут звать?",
    options: ["Тото", "Бачок", "Денис"],
  },
  phase: "initial",
  generation: "idle",
  error: null,
  retryTarget: null,
};

function renderCreate(overrides: Partial<Parameters<typeof CreateScreen>[0]> = {}) {
  const dispatch = vi.fn(async () => undefined);
  const props: Parameters<typeof CreateScreen>[0] = {
    state: initial,
    busy: false,
    reducedMotion: true,
    customOpen: false,
    customValue: "",
    onOpenCustom: vi.fn(),
    onCloseCustom: vi.fn(),
    onUpdateCustom: vi.fn(),
    feedback: vi.fn(),
    dispatch,
    ...overrides,
  };
  return { ...render(<CreateScreen {...props} />), dispatch, props };
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("Android Create presentation", () => {
  it("keeps every canonical option label complete without ellipsis substitution", () => {
    renderCreate();

    const expectedLabels = [...initial.options, "Свой вариант"];
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(expectedLabels.length);
    expect(buttons.map((button) => button.textContent)).toEqual(expectedLabels);
    buttons.forEach((button, index) => {
      expect(button).toHaveAccessibleName(expectedLabels[index]);
      expect(button.textContent).not.toContain("…");
    });
  });

  it("routes Create controls to the exact native feedback kinds", () => {
    const feedback = vi.fn();
    const { rerender, props } = renderCreate({ feedback });

    fireEvent.click(screen.getByRole("button", { name: "Ледяного дракона" }));
    expect(feedback).toHaveBeenLastCalledWith("createAnswer");

    rerender(<CreateScreen {...props} feedback={feedback} />);
    fireEvent.click(screen.getByRole("button", { name: "Свой вариант" }));
    expect(feedback).toHaveBeenLastCalledWith("createCustom");
  });

  it("projects the next question immediately while the native answer is pending", async () => {
    let settle!: () => void;
    const dispatch = vi.fn(() => new Promise<void>((resolve) => {
      settle = resolve;
    }));
    const { container } = renderCreate({ dispatch, reducedMotion: false });

    fireEvent.click(screen.getByRole("button", { name: "Ледяного дракона" }));

    expect(dispatch).toHaveBeenCalledWith(
      "CREATE_ANSWER",
      { answer: "Ледяного дракона", step: 0 },
    );
    expect(screen.getByRole("heading", { name: "Как его будут звать?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Тото" })).toBeEnabled();
    expect(container.querySelector(".screen--create")).toHaveAttribute("data-phase", "transition");

    await act(async () => settle());
  });

  it("keeps the latest optimistic answer visible when an older dispatch settles", async () => {
    const settlers: Array<() => void> = [];
    const dispatch = vi.fn(() => new Promise<void>((resolve) => settlers.push(resolve)));
    renderCreate({ dispatch });

    fireEvent.click(screen.getByRole("button", { name: "Ледяного дракона" }));
    fireEvent.click(screen.getByRole("button", { name: "Тото" }));
    expect(dispatch).toHaveBeenCalledTimes(2);

    await act(async () => settlers[0]());
    expect(screen.getByRole("heading", { name: "Как его будут звать?" })).toBeInTheDocument();

    await act(async () => settlers[1]());
  });

  it("projects the final answer directly into the generation stage", async () => {
    let settle!: () => void;
    const dispatch = vi.fn(() => new Promise<void>((resolve) => {
      settle = resolve;
    }));
    renderCreate({
      dispatch,
      state: {
        ...initial,
        step: 4,
        title: "Какой у него любимый предмет?",
        options: ["Вантуз", "Рулон бумаги", "Кока кола"],
        nextQuestion: null,
        phase: "formed",
        generation: "running",
      },
    });

    fireEvent.click(screen.getByRole("button", { name: "Вантуз" }));

    expect(screen.getByRole("heading", { name: "Персонаж формируется..." })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Персонаж формируется");
    expect(document.querySelector(".creation-generation")).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("button", { name: "Вантуз" })).not.toBeInTheDocument();
    await act(async () => settle());
  });

  it("keeps custom draft local and exposes Next only for a nonblank value", () => {
    const onUpdateCustom = vi.fn();
    const { dispatch } = renderCreate({ customOpen: true, onUpdateCustom });

    expect(screen.getByText("Кого хочешь создать?")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Далее" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Свой вариант персонажа"), {
      target: { value: "Космический кот" },
    });

    expect(onUpdateCustom).toHaveBeenCalledWith("Космический кот");
    expect(dispatch).not.toHaveBeenCalled();
  });

  it("restores keyboard focus to the custom-answer trigger after Back", () => {
    const { rerender, props } = renderCreate();
    const trigger = screen.getByRole("button", { name: "Свой вариант" });
    trigger.focus();
    fireEvent.click(trigger);

    rerender(<CreateScreen {...props} customOpen />);
    expect(screen.getByLabelText("Свой вариант персонажа")).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Назад" }));
    expect(props.onCloseCustom).toHaveBeenCalledOnce();
    rerender(<CreateScreen {...props} customOpen={false} />);

    expect(screen.getByRole("button", { name: "Свой вариант" })).toHaveFocus();
  });

  it("closes custom input immediately when its trimmed answer is submitted", () => {
    const onCloseCustom = vi.fn();
    const { dispatch } = renderCreate({
      customOpen: true,
      customValue: "  Космический кот  ",
      onCloseCustom,
    });

    fireEvent.click(screen.getByRole("button", { name: "Далее" }));

    expect(onCloseCustom).toHaveBeenCalledTimes(1);
    expect(dispatch).toHaveBeenCalledWith(
      "CREATE_ANSWER",
      { answer: "Космический кот", step: 0 },
    );
  });

  it("forms the background and acknowledges the completed transition exactly once", () => {
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => undefined);
    const { container, dispatch } = renderCreate({
      reducedMotion: false,
      state: {
        ...initial,
        step: 1,
        title: "Как его будут звать?",
        options: ["Тото", "Бачок", "Денис"],
        nextQuestion: {
          title: "Какой у него характер?",
          options: ["Добрый", "Злой", "Ленивый"],
        },
        phase: "transition",
        generation: "running",
      },
    });
    const video = container.querySelector("video") as HTMLVideoElement;
    video.currentTime = 267 / 24;

    fireEvent.timeUpdate(video);
    fireEvent.timeUpdate(video);

    expect(container.querySelector(".screen--create")).toHaveAttribute("data-phase", "formed");
    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(dispatch).toHaveBeenCalledWith("CREATE_BACKGROUND_COMPLETE");
  });

  it("replays one failed background acknowledgement and confirms it only after success", async () => {
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => undefined);
    const dispatch = vi.fn()
      .mockRejectedValueOnce(new Error("temporary"))
      .mockResolvedValueOnce(undefined);
    const { container } = renderCreate({
      dispatch,
      reducedMotion: false,
      state: {
        ...initial,
        step: 1,
        title: "Как его будут звать?",
        options: ["Тото", "Бачок", "Денис"],
        phase: "transition",
        generation: "running",
      },
    });

    let video = container.querySelector("video") as HTMLVideoElement;
    video.currentTime = 267 / 24;
    fireEvent.timeUpdate(video);
    expect(dispatch).toHaveBeenCalledTimes(1);

    await act(async () => Promise.resolve());
    expect(container.querySelector(".screen--create")).toHaveAttribute("data-phase", "transition");
    video = container.querySelector("video") as HTMLVideoElement;
    video.currentTime = 267 / 24;
    fireEvent.timeUpdate(video);
    await act(async () => Promise.resolve());

    expect(dispatch).toHaveBeenCalledTimes(2);
    expect(container.querySelector(".screen--create")).toHaveAttribute("data-phase", "formed");
    fireEvent.timeUpdate(video);
    expect(dispatch).toHaveBeenCalledTimes(2);
  });

  it("shows a native persistence error and retries through one native command", () => {
    const feedback = vi.fn();
    const { dispatch } = renderCreate({
      feedback,
      state: {
        ...initial,
        step: 5,
        title: "",
        options: [],
        nextQuestion: null,
        phase: "formed",
        generation: "retryable",
        error: "Не удалось сохранить создание. Попробуйте ещё раз.",
      },
    });

    expect(screen.getByText("Персонаж формируется...")).toBeInTheDocument();
    const alert = screen.getByRole("alert");
    const retry = screen.getByRole("button", { name: "Попробовать снова" });
    expect(alert).toHaveTextContent("Не удалось сохранить создание. Попробуйте ещё раз.");
    expect(alert).not.toContainElement(retry);
    expect(document.querySelector(".creation-generation")).toHaveAttribute("aria-busy", "false");
    fireEvent.click(retry);
    expect(dispatch).toHaveBeenCalledWith("CREATE_RETRY");
    expect(feedback).toHaveBeenCalledWith("createRetry");
  });

  it("uses the native generation failure copy when the snapshot omits an error", () => {
    renderCreate({
      state: {
        ...initial,
        step: 5,
        title: "",
        options: [],
        nextQuestion: null,
        phase: "formed",
        generation: "retryable",
        error: null,
      },
    });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Не получилось создать питомца. Попробуйте ещё раз.",
    );
  });

  it("shows the native mosaic loader and finishes automatically after 900 ms", async () => {
    vi.useFakeTimers();
    const { dispatch } = renderCreate({
      reducedMotion: false,
      state: {
        ...initial,
        step: 5,
        title: "",
        options: [],
        nextQuestion: null,
        phase: "formed",
        generation: "ready",
      },
    });

    const creating = screen.getByLabelText("Создаем друга");
    expect(creating).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("status")).toHaveTextContent("Создаем друга");
    await act(async () => vi.advanceTimersByTime(899));
    expect(dispatch).not.toHaveBeenCalled();
    await act(async () => vi.advanceTimersByTime(1));
    expect(dispatch).toHaveBeenCalledWith("CREATE_FINISH");
  });

  it("retries the finish timer once when native does not confirm it", async () => {
    vi.useFakeTimers();
    const dispatch = vi.fn()
      .mockRejectedValueOnce(new Error("temporary"))
      .mockResolvedValueOnce(undefined);
    renderCreate({
      dispatch,
      reducedMotion: false,
      state: {
        ...initial,
        step: 5,
        title: "",
        options: [],
        nextQuestion: null,
        phase: "formed",
        generation: "ready",
      },
    });

    await act(async () => vi.advanceTimersByTime(900));
    expect(dispatch).toHaveBeenCalledTimes(1);
    await act(async () => vi.advanceTimersByTime(899));
    expect(dispatch).toHaveBeenCalledTimes(1);
    await act(async () => vi.advanceTimersByTime(1));
    expect(dispatch).toHaveBeenCalledTimes(2);
    await act(async () => vi.advanceTimersByTime(5_000));
    expect(dispatch).toHaveBeenCalledTimes(2);
  });
});
