import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CreationMosaic } from "./CreationMosaic";

class ControlledImage {
  static instances: ControlledImage[] = [];

  decoding = "auto";
  onload: ((event: Event) => void) | null = null;
  onerror: ((event: Event | string) => void) | null = null;
  src = "";

  constructor() {
    ControlledImage.instances.push(this);
  }
}

const context = {
  clearRect: vi.fn(),
  drawImage: vi.fn(),
  imageSmoothingEnabled: true,
};

async function finishImagePreload() {
  await act(async () => {
    ControlledImage.instances.forEach((image) => image.onload?.(new Event("load")));
    await Promise.resolve();
  });
}

describe("CreationMosaic", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    ControlledImage.instances = [];
    context.clearRect.mockClear();
    context.drawImage.mockClear();
    context.imageSmoothingEnabled = true;
    vi.stubGlobal("Image", ControlledImage);
    Object.defineProperty(HTMLCanvasElement.prototype, "getContext", {
      configurable: true,
      value: vi.fn(() => context),
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    Object.defineProperty(HTMLCanvasElement.prototype, "getContext", {
      configurable: true,
      value: () => null,
    });
  });

  it("preprocesses the exact three frames and crossfades persistent layers every 720 ms", async () => {
    const { container } = render(<CreationMosaic reducedMotion={false} />);

    expect(ControlledImage.instances.map((image) => image.src)).toEqual([
      "/res/main_pet.png",
      "/res/pet.png",
      "/res/main_pet.png",
    ]);
    await finishImagePreload();

    const layers = Array.from(container.querySelectorAll("canvas"));
    expect(layers).toHaveLength(3);
    expect(layers.map((layer) => layer.dataset.edge)).toEqual(["18", "24", "21"]);
    expect(layers.map((layer) => layer.dataset.active)).toEqual(["true", "false", "false"]);
    expect(layers[0]).toHaveStyle({
      opacity: "1",
      transition: "none",
      imageRendering: "pixelated",
    });
    expect(context.drawImage).toHaveBeenCalledTimes(6);
    expect(context.imageSmoothingEnabled).toBe(false);

    act(() => vi.advanceTimersByTime(719));
    expect(layers.map((layer) => layer.dataset.active)).toEqual(["true", "false", "false"]);
    act(() => vi.advanceTimersByTime(1));
    expect(layers.map((layer) => layer.dataset.active)).toEqual(["false", "true", "false"]);
    expect(layers[0]).toHaveStyle({
      opacity: "0",
      transition: "opacity 260ms cubic-bezier(0.4, 0, 0.2, 1)",
    });
    expect(layers[1]).toHaveStyle({
      opacity: "1",
      transition: "opacity 260ms cubic-bezier(0.4, 0, 0.2, 1)",
    });

    act(() => vi.advanceTimersByTime(720));
    expect(layers.map((layer) => layer.dataset.active)).toEqual(["false", "false", "true"]);
    act(() => vi.advanceTimersByTime(720));
    expect(layers.map((layer) => layer.dataset.active)).toEqual(["true", "false", "false"]);
  });

  it("renders an unpixelated main_pet still and performs no image processing in reduced motion", () => {
    const { container } = render(<CreationMosaic reducedMotion />);

    expect(ControlledImage.instances).toHaveLength(0);
    expect(container.querySelector("canvas")).toBeNull();
    const still = container.querySelector("img");
    expect(still).toHaveAttribute("src", "/res/main_pet.png");
    expect(still).toHaveStyle({
      animation: "none",
      imageRendering: "auto",
    });
  });

  it("ignores stale image completions after switching to reduced motion", async () => {
    const { container, rerender } = render(<CreationMosaic reducedMotion={false} />);
    const staleLoads = ControlledImage.instances.map((image) => image.onload);

    rerender(<CreationMosaic reducedMotion />);
    await act(async () => {
      staleLoads.forEach((load) => load?.(new Event("load")));
      await Promise.resolve();
    });

    expect(container.querySelector("canvas")).toBeNull();
    expect(container.querySelector('img[src="/res/main_pet.png"]')).not.toBeNull();
  });
});
