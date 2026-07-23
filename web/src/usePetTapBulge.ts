import { useEffect, useRef, useState } from "react";
import { motionConfig } from "./motionConfig";
import { createBulgeRenderer, type BulgeRenderer } from "./petTapBulgeRenderer";

function smoothStep(value: number): number {
  const bounded = Math.max(0, Math.min(1, value));
  return bounded * bounded * (3 - 2 * bounded);
}

export function petTapBulgeStrength(elapsed: number, reducedMotion: boolean): number {
  const contract = reducedMotion
    ? motionConfig.reducedMotion.petTapBulge
    : motionConfig.petTap.bulge;
  if (elapsed < 0 || elapsed >= contract.durationMillis) return 0;
  const attack = smoothStep(elapsed / contract.attackMillis);
  const release = elapsed <= contract.holdUntilMillis
    ? 1
    : smoothStep(
        1 - (elapsed - contract.holdUntilMillis) /
          contract.releaseMillis,
      );
  return contract.strength * Math.min(attack, release);
}

export function usePetTapBulge(reducedMotion: boolean) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rendererRef = useRef<BulgeRenderer | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const frameRef = useRef<number | null>(null);
  const startedAtRef = useRef(0);
  const centerRef = useRef({ x: 0.5, y: 0.5 });
  const [visible, setVisible] = useState(false);

  const draw = (now: number) => {
    const elapsed = now - startedAtRef.current;
    const duration = reducedMotion
      ? motionConfig.reducedMotion.petTapBulge.durationMillis
      : motionConfig.petTap.bulge.durationMillis;
    if (elapsed >= duration) {
      frameRef.current = null;
      setVisible(false);
      return;
    }
    const video = videoRef.current;
    if (video) rendererRef.current?.render(video, centerRef.current, petTapBulgeStrength(elapsed, reducedMotion));
    frameRef.current = window.requestAnimationFrame(draw);
  };

  const trigger = (
    video: HTMLVideoElement | null,
    normalizedX: number,
    normalizedY: number,
    width: number,
    height: number,
  ) => {
    const canvas = canvasRef.current;
    if (!canvas || !video) return false;
    if (!rendererRef.current) {
      try {
        rendererRef.current = createBulgeRenderer(canvas);
      } catch {
        rendererRef.current = null;
      }
    }
    const renderer = rendererRef.current;
    if (!renderer) return false;
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    renderer.resize(Math.round(width * pixelRatio), Math.round(height * pixelRatio));
    videoRef.current = video;
    centerRef.current = {
      x: Math.max(0, Math.min(1, normalizedX)),
      y: 1 - Math.max(0, Math.min(1, normalizedY)),
    };
    startedAtRef.current = performance.now();
    setVisible(true);
    if (frameRef.current === null) frameRef.current = window.requestAnimationFrame(draw);
    return true;
  };

  useEffect(() => () => {
    if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current);
    rendererRef.current?.destroy();
    rendererRef.current = null;
  }, []);

  return { canvasRef, visible, trigger };
}
