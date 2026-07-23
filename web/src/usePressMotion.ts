import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FocusEventHandler,
  type AnimationEventHandler,
  type KeyboardEventHandler,
  type PointerEventHandler,
  type RefObject,
} from "react";
import {
  createSpringScaleMotion,
  createTweenScaleMotion,
  sampleScaleMotion,
  type ScaleMotion,
} from "./pressMotionModel";
import { motionConfig } from "./motionConfig";

export type PressMotionPhase = "idle" | "pressing" | "releasing";
export type PressMotionKind = "dashboardSpring" | "eventSpring" | "storyTween";

interface PressMotionOptions {
  disabled: boolean;
  reducedMotion: boolean;
  kind?: PressMotionKind;
  releaseDurationMillis?: number;
}

interface PressMotionBindings<T extends HTMLElement> {
  ref: RefObject<T | null>;
  phase: PressMotionPhase;
  onPointerDown: PointerEventHandler<T>;
  onPointerUp: PointerEventHandler<T>;
  onPointerCancel: PointerEventHandler<T>;
  onPointerLeave: PointerEventHandler<T>;
  onKeyDown: KeyboardEventHandler<T>;
  onKeyUp: KeyboardEventHandler<T>;
  onBlur: FocusEventHandler<T>;
  onAnimationEnd: AnimationEventHandler<T>;
}

interface ActiveMotion {
  motion: ScaleMotion;
  startedAt: number;
  animation: Animation | null;
}

function scaleTransform(scale: number): string {
  return `scale(${scale.toFixed(6)})`;
}

function springLinearEasing(motion: ScaleMotion): string {
  if (motion.kind !== "spring" || motion.from === motion.to) return "linear";
  return `linear(${motion.keyframes.map(({ offset, scale }) => {
    const progress = (scale - motion.from) / (motion.to - motion.from);
    return `${progress.toFixed(6)} ${(offset * 100).toFixed(3)}%`;
  }).join(", ")})`;
}

function animationKeyframes(motion: ScaleMotion): Keyframe[] {
  return motion.keyframes.map(({ offset, scale }) => ({
    offset,
    transform: scaleTransform(scale),
  }));
}

/**
 * Interruptible Android-matched scale motion. The active analytical curve supplies the exact
 * presentation value (and spring velocity) when direction changes, so release never restarts from
 * a hard-coded keyframe.
 */
export function usePressMotion<T extends HTMLElement>({
  disabled,
  reducedMotion,
  kind,
  releaseDurationMillis = motionConfig.reducedMotion.instantDurationMillis,
}: PressMotionOptions): PressMotionBindings<T> {
  const ref = useRef<T>(null);
  const [phase, setPhase] = useState<PressMotionPhase>("idle");
  const phaseRef = useRef<PressMotionPhase>("idle");
  const activeRef = useRef<ActiveMotion | null>(null);
  const finishTimerRef = useRef<number | null>(null);

  const clearFinishTimer = useCallback(() => {
    if (finishTimerRef.current === null) return;
    window.clearTimeout(finishTimerRef.current);
    finishTimerRef.current = null;
  }, []);

  const runTo = useCallback((target: number, nextPhase: PressMotionPhase) => {
    const element = ref.current;
    if (!element) return;
    const now = performance.now();
    const active = activeRef.current;
    const current = active
      ? sampleScaleMotion(active.motion, now - active.startedAt)
      : { value: Number.parseFloat(element.dataset.pressScale ?? "1"), velocity: 0 };

    clearFinishTimer();
    if (active) {
      element.style.transition = "none";
      element.style.transform = scaleTransform(current.value);
      element.dataset.pressScale = String(current.value);
      active.animation?.cancel();
      activeRef.current = null;
    }

    const springSpec = kind === "dashboardSpring"
      ? nextPhase === "pressing"
        ? motionConfig.press.dashboardPressSpring
        : motionConfig.press.dashboardReleaseSpring
      : nextPhase === "pressing"
        ? motionConfig.press.eventPressSpring
        : motionConfig.press.eventReleaseSpring;
    const motion = kind === "dashboardSpring" || kind === "eventSpring"
      ? createSpringScaleMotion(
          current.value,
          target,
          current.velocity,
          springSpec,
        )
      : createTweenScaleMotion(
          current.value,
          target,
          reducedMotion
            ? motionConfig.reducedMotion.instantDurationMillis
            : motionConfig.press.storyDurationMillis,
          motionConfig.easing.fastOutSlowIn.curve,
        );
    const displayedPhase = motion.durationMillis === 0 && nextPhase === "releasing"
      ? "idle"
      : nextPhase;
    phaseRef.current = displayedPhase;
    setPhase(displayedPhase);
    element.dataset.pressScale = String(target);
    element.style.transition = "none";

    if (motion.durationMillis === 0) {
      element.style.transform = scaleTransform(target);
      return;
    }

    const startedAt = performance.now();
    let animation: Animation | null = null;
    if (typeof element.animate === "function") {
      animation = element.animate(animationKeyframes(motion), {
        duration: motion.durationMillis,
        easing: motion.kind === "tween"
          ? `cubic-bezier(${motion.easing.join(", ")})`
          : "linear",
        fill: "forwards",
      });
    } else {
      // JSDOM and old WebViews: CSS transitions preserve the current presentation value too.
      void element.offsetWidth;
      element.style.transition = `transform ${motion.durationMillis}ms ${
        motion.kind === "spring"
          ? springLinearEasing(motion)
          : `cubic-bezier(${motion.easing.join(", ")})`
      }`;
      element.style.transform = scaleTransform(target);
    }

    const activeMotion: ActiveMotion = { motion, startedAt, animation };
    activeRef.current = activeMotion;
    finishTimerRef.current = window.setTimeout(() => {
      if (activeRef.current !== activeMotion) return;
      element.style.transform = scaleTransform(target);
      element.dataset.pressScale = String(target);
      animation?.cancel();
      activeRef.current = null;
      finishTimerRef.current = null;
      if (nextPhase === "releasing") {
        phaseRef.current = "idle";
        setPhase("idle");
      }
    }, motion.durationMillis);
  }, [clearFinishTimer, kind, reducedMotion]);

  useEffect(() => {
    if (disabled) {
      if (kind && phaseRef.current !== "idle") {
        runTo(1, "releasing");
      } else {
        phaseRef.current = "idle";
        setPhase("idle");
      }
    }
  }, [disabled, kind, runTo]);

  useEffect(() => {
    if (kind || phase !== "releasing") return;
    const timer = window.setTimeout(() => {
      phaseRef.current = "idle";
      setPhase("idle");
    }, releaseDurationMillis);
    return () => window.clearTimeout(timer);
  }, [kind, phase, releaseDurationMillis]);

  useEffect(() => () => {
    clearFinishTimer();
    activeRef.current?.animation?.cancel();
    activeRef.current = null;
  }, [clearFinishTimer]);

  const press = () => {
    if (!disabled && phaseRef.current !== "pressing") {
      if (kind) {
        runTo(
          kind === "dashboardSpring"
            ? motionConfig.press.dashboardPressedScale
            : kind === "eventSpring"
              ? motionConfig.press.eventPressedScale
              : motionConfig.press.storyPressedScale,
          "pressing",
        );
      } else {
        phaseRef.current = "pressing";
        setPhase("pressing");
      }
    }
  };
  const release = () => {
    if (phaseRef.current === "idle") return;
    if (kind) {
      runTo(1, "releasing");
    } else {
      const nextPhase = reducedMotion ? "idle" : "releasing";
      phaseRef.current = nextPhase;
      setPhase(nextPhase);
    }
  };

  return {
    ref,
    phase,
    onPointerDown(event) {
      if (event.button !== 0) return;
      press();
    },
    onPointerUp: release,
    onPointerCancel: release,
    onPointerLeave: release,
    onKeyDown(event) {
      if (event.repeat) return;
      if (event.key === " " || event.key === "Enter") press();
    },
    onKeyUp(event) {
      if (event.key === " " || event.key === "Enter") release();
    },
    onBlur: release,
    onAnimationEnd() {
      if (kind || phaseRef.current !== "releasing") return;
      phaseRef.current = "idle";
      setPhase("idle");
    },
  };
}
