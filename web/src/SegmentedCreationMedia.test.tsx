import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SegmentedCreationMedia } from "./SegmentedCreationMedia";

type FrameCallback = (now: number, metadata: { mediaTime: number }) => void;

class VideoFrameDriver {
  private nextId = 1;
  readonly callbacks = new Map<number, FrameCallback>();
  readonly cancelled: number[] = [];

  request = (callback: FrameCallback) => {
    const id = this.nextId++;
    this.callbacks.set(id, callback);
    return id;
  };

  cancel = (id: number) => {
    this.cancelled.push(id);
    this.callbacks.delete(id);
  };

  render(mediaTime: number) {
    const entry = this.callbacks.entries().next().value as [number, FrameCallback] | undefined;
    if (!entry) throw new Error("No pending video frame callback");
    const [id, callback] = entry;
    this.callbacks.delete(id);
    callback(performance.now(), { mediaTime });
  }
}

function markVideoReady(video: HTMLVideoElement, readyState = HTMLMediaElement.HAVE_CURRENT_DATA) {
  Object.defineProperty(video, "readyState", { configurable: true, value: readyState });
  fireEvent.loadedMetadata(video);
}

describe("SegmentedCreationMedia", () => {
  let frames: VideoFrameDriver;
  let play: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    frames = new VideoFrameDriver();
    Object.defineProperty(HTMLVideoElement.prototype, "requestVideoFrameCallback", {
      configurable: true,
      value: frames.request,
    });
    Object.defineProperty(HTMLVideoElement.prototype, "cancelVideoFrameCallback", {
      configurable: true,
      value: frames.cancel,
    });
    play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => undefined);
  });

  afterEach(() => {
    Reflect.deleteProperty(HTMLVideoElement.prototype, "requestVideoFrameCallback");
    Reflect.deleteProperty(HTMLVideoElement.prototype, "cancelVideoFrameCallback");
    vi.restoreAllMocks();
  });

  it("uses the true [170, 267) transition boundary and completes exactly once", () => {
    const onTransitionComplete = vi.fn();
    const { container } = render(
      <SegmentedCreationMedia
        phase="transition"
        reducedMotion={false}
        dimmed={false}
        onTransitionComplete={onTransitionComplete}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    markVideoReady(video);

    expect(video.currentTime).toBe(170 / 24);
    act(() => frames.render(170 / 24));
    expect(screen.queryByTestId("creation-phase-poster")).not.toBeInTheDocument();

    video.currentTime = 267 / 24 - 0.000_001;
    act(() => frames.render(video.currentTime));
    expect(onTransitionComplete).not.toHaveBeenCalled();

    video.currentTime = 267 / 24;
    act(() => frames.render(video.currentTime));
    expect(video.currentTime).toBe(267 / 24);
    expect(onTransitionComplete).toHaveBeenCalledTimes(1);

    fireEvent.timeUpdate(video);
    fireEvent.timeUpdate(video);
    expect(onTransitionComplete).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["initial", 0, 170 / 24],
    ["formed", 267 / 24, 447 / 24],
  ] as const)("loops the %s segment at its exact end", (phase, start, end) => {
    const onTransitionComplete = vi.fn();
    const { container } = render(
      <SegmentedCreationMedia
        phase={phase}
        reducedMotion={false}
        dimmed={false}
        onTransitionComplete={onTransitionComplete}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    markVideoReady(video);
    expect(video.currentTime).toBe(start);

    video.currentTime = end;
    act(() => frames.render(end));

    expect(video.currentTime).toBe(start);
    expect(play).toHaveBeenCalledTimes(2);
    expect(onTransitionComplete).not.toHaveBeenCalled();
    expect(frames.callbacks.size).toBe(1);
  });

  it("keeps an explicit phase poster until the first rendered frame and restores it on error", () => {
    const onTransitionComplete = vi.fn();
    const { container } = render(
      <SegmentedCreationMedia
        phase="transition"
        reducedMotion={false}
        dimmed={false}
        onTransitionComplete={onTransitionComplete}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    expect(screen.getByTestId("creation-phase-poster")).toHaveAttribute(
      "src",
      "/res/clouds_formed.png",
    );

    markVideoReady(video);
    act(() => frames.render(170 / 24));
    expect(screen.queryByTestId("creation-phase-poster")).not.toBeInTheDocument();

    fireEvent.error(video);
    expect(screen.getByTestId("creation-phase-poster")).toHaveAttribute(
      "src",
      "/res/clouds_formed.png",
    );
    video.currentTime = 267 / 24;
    fireEvent.timeUpdate(video);
    expect(onTransitionComplete).not.toHaveBeenCalled();
  });

  it("uses the canonical static poster and creates no video with reduced motion", () => {
    const { container, rerender } = render(
      <SegmentedCreationMedia
        phase="initial"
        reducedMotion
        dimmed={false}
        onTransitionComplete={vi.fn()}
      />,
    );
    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector('img[src="/res/clouds_empty.png"]')).not.toBeNull();

    rerender(
      <SegmentedCreationMedia
        phase="transition"
        reducedMotion
        dimmed={false}
        onTransitionComplete={vi.fn()}
      />,
    );
    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector('img[src="/res/clouds_formed.png"]')).not.toBeNull();
  });

  it("falls back to paint-safe RAF polling and cancels it on teardown", () => {
    Reflect.deleteProperty(HTMLVideoElement.prototype, "requestVideoFrameCallback");
    Reflect.deleteProperty(HTMLVideoElement.prototype, "cancelVideoFrameCallback");

    let nextId = 1;
    const callbacks = new Map<number, FrameRequestCallback>();
    const request = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      const id = nextId++;
      callbacks.set(id, callback);
      return id;
    });
    const cancel = vi.spyOn(window, "cancelAnimationFrame").mockImplementation((id) => {
      callbacks.delete(id);
    });
    const runFrame = () => {
      const entry = callbacks.entries().next().value as [number, FrameRequestCallback] | undefined;
      if (!entry) throw new Error("No pending animation frame");
      const [id, callback] = entry;
      callbacks.delete(id);
      callback(performance.now());
    };

    const { container, unmount } = render(
      <SegmentedCreationMedia
        phase="initial"
        reducedMotion={false}
        dimmed={false}
        onTransitionComplete={vi.fn()}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    markVideoReady(video);

    act(runFrame);
    expect(screen.getByTestId("creation-phase-poster")).toBeInTheDocument();
    act(runFrame);
    expect(screen.queryByTestId("creation-phase-poster")).not.toBeInTheDocument();

    unmount();
    expect(request).toHaveBeenCalled();
    expect(cancel).toHaveBeenCalledTimes(1);
  });
});
