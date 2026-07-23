import { createRef } from "react";
import { act, fireEvent, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PetMediaSnapshot } from "./contracts";
import { DashboardSceneMedia } from "./DashboardSceneMedia";

const media: PetMediaSnapshot = {
  videoRef: "/res/pet_normal.mp4",
  posterRef: "/res/pet_poster.webp",
  sadVideoRef: null,
  happyVideoRef: null,
};

describe("dashboard scene media", () => {
  it("keeps the poster through the first rendered video frame, then starts the 120ms crossfade", () => {
    let renderFrame: (() => void) | null = null;
    const { container } = render(
      <DashboardSceneMedia
        media={media}
        mode="chat"
        reducedMotion={false}
        videoRef={createRef<HTMLVideoElement>()}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    Object.defineProperty(video, "requestVideoFrameCallback", {
      configurable: true,
      value: vi.fn((callback: () => void) => {
        renderFrame = callback;
        return 4;
      }),
    });
    expect(container.querySelector(".scene-poster")).not.toHaveClass("scene-poster--hidden");
    expect(video).not.toHaveClass("scene-video--ready");
    fireEvent.playing(video);
    expect(video).not.toHaveClass("scene-video--ready");

    act(() => renderFrame?.());
    expect(video).toHaveClass("scene-video--ready");
    expect(container.querySelector(".scene-poster")).toHaveClass("scene-poster--hidden");
    expect(container.querySelector(".dashboard-media")).toHaveClass("dashboard-media--ime-shifted");
    expect(container.querySelector(".scene-filter")).toHaveAttribute("src", "/res/video_filter_normal.webp");
  });

  it("uses a poster-only reduced-motion scene and exposes the native retry after failure", () => {
    const { container, rerender } = render(
      <DashboardSceneMedia
        media={media}
        mode="idle"
        reducedMotion
        videoRef={createRef<HTMLVideoElement>()}
      />,
    );
    expect(container.querySelector("video")).not.toBeInTheDocument();
    expect(container.querySelector(".scene-poster")).toHaveAttribute("src", media.posterRef);

    rerender(
      <DashboardSceneMedia
        media={media}
        mode="idle"
        reducedMotion={false}
        videoRef={createRef<HTMLVideoElement>()}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    fireEvent.error(video);
    expect(container.querySelector("video")).toBeInTheDocument();
    expect(container.querySelector(".scene-poster")).not.toHaveClass("scene-poster--hidden");
    const retry = container.querySelector(".scene-media-retry") as HTMLButtonElement;
    expect(retry).toHaveTextContent("Повторить медиа");
    fireEvent.click(retry);
    expect(container.querySelector(".scene-media-retry")).not.toBeInTheDocument();
    expect(container.querySelector("video")).not.toBe(video);
  });

  it("pauses and resumes the same video element across lifecycle events", () => {
    const videoRef = createRef<HTMLVideoElement>();
    const { container, rerender } = render(
      <DashboardSceneMedia
        media={media}
        mode="idle"
        reducedMotion={false}
        foreground
        videoRef={videoRef}
      />,
    );
    const video = container.querySelector("video") as HTMLVideoElement;
    const poster = container.querySelector(".scene-poster");
    const pause = vi.spyOn(video, "pause").mockImplementation(() => undefined);
    const play = vi.spyOn(video, "play").mockResolvedValue(undefined);

    rerender(
      <DashboardSceneMedia
        media={media}
        mode="idle"
        reducedMotion={false}
        foreground={false}
        videoRef={videoRef}
      />,
    );
    expect(container.querySelector("video")).toBe(video);
    expect(container.querySelector(".scene-poster")).toBe(poster);
    expect(pause).toHaveBeenCalledTimes(1);

    rerender(
      <DashboardSceneMedia
        media={media}
        mode="idle"
        reducedMotion={false}
        foreground
        videoRef={videoRef}
      />,
    );
    expect(container.querySelector("video")).toBe(video);
    expect(play).toHaveBeenCalledTimes(1);
  });
});
