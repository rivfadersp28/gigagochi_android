import { motionConfig } from "./motionConfig";

export interface SpringScaleSpec {
  dampingRatio: number;
  stiffness: number;
}

export interface ScaleMotionSample {
  value: number;
  velocity: number;
}

export interface ScaleMotionKeyframe {
  offset: number;
  scale: number;
}

export interface SpringScaleMotion {
  kind: "spring";
  from: number;
  to: number;
  initialVelocity: number;
  durationMillis: number;
  spec: SpringScaleSpec;
  keyframes: ScaleMotionKeyframe[];
}

export interface TweenScaleMotion {
  kind: "tween";
  from: number;
  to: number;
  durationMillis: number;
  easing: readonly [number, number, number, number];
  keyframes: ScaleMotionKeyframe[];
}

export type ScaleMotion = SpringScaleMotion | TweenScaleMotion;

export const EVENT_PRESS_SPRING = motionConfig.press.eventPressSpring;
export const EVENT_RELEASE_SPRING = motionConfig.press.eventReleaseSpring;
export const DASHBOARD_PRESS_SPRING = motionConfig.press.dashboardPressSpring;
export const DASHBOARD_RELEASE_SPRING = motionConfig.press.dashboardReleaseSpring;
export const STORY_PRESS_DURATION_MILLIS = motionConfig.press.storyDurationMillis;
export const FAST_OUT_SLOW_IN = motionConfig.easing.fastOutSlowIn.curve;

function springSample(
  from: number,
  to: number,
  initialVelocity: number,
  spec: SpringScaleSpec,
  elapsedMillis: number,
): ScaleMotionSample {
  const elapsedSeconds = Math.max(0, elapsedMillis) / 1_000;
  const naturalFrequency = Math.sqrt(spec.stiffness);
  const displacement = from - to;
  if (spec.dampingRatio === 1) {
    const coefficient = initialVelocity + naturalFrequency * displacement;
    const decay = Math.exp(-naturalFrequency * elapsedSeconds);
    const value = to + (displacement + coefficient * elapsedSeconds) * decay;
    const velocity = (
      coefficient - naturalFrequency * (displacement + coefficient * elapsedSeconds)
    ) * decay;
    return { value, velocity };
  }

  const decayRate = spec.dampingRatio * naturalFrequency;
  const dampedFrequency = naturalFrequency * Math.sqrt(1 - spec.dampingRatio ** 2);
  const sineCoefficient = (initialVelocity + decayRate * displacement) / dampedFrequency;
  const angle = dampedFrequency * elapsedSeconds;
  const decay = Math.exp(-decayRate * elapsedSeconds);
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  const value = to + decay * (displacement * cos + sineCoefficient * sin);
  const velocity = decay * (
    (-decayRate * displacement + dampedFrequency * sineCoefficient) * cos +
    (-decayRate * sineCoefficient - dampedFrequency * displacement) * sin
  );
  return { value, velocity };
}

function iterateNewton(
  initial: number,
  fn: (time: number) => number,
  derivative: (time: number) => number,
): number {
  const slope = derivative(initial);
  if (!Number.isFinite(slope) || Math.abs(slope) < Number.EPSILON) return initial;
  return initial - fn(initial) / slope;
}

function estimateCriticalDurationSeconds(
  root: number,
  initialPosition: number,
  initialVelocity: number,
): number {
  const c1 = initialPosition;
  const c2 = initialVelocity - root * c1;
  const t1 = Math.log(Math.abs(1 / c1)) / root;
  let guess = Math.log(Math.abs(1 / c2));
  let t2 = guess;
  for (let index = 0; index <= 5; index += 1) {
    t2 = guess - Math.log(Math.abs(t2 / root));
  }
  t2 /= root;
  let current = !Number.isFinite(t1) ? t2 : !Number.isFinite(t2) ? t1 : Math.max(t1, t2);

  const inflectionTime = -(root * c1 + c2) / (root * c2);
  const inflectionValue = (c1 + c2 * inflectionTime) * Math.exp(root * inflectionTime);
  let signedDelta: number;
  if (!Number.isFinite(inflectionTime) || inflectionTime <= 0) {
    signedDelta = -1;
  } else if (-inflectionValue < 1) {
    if (c2 < 0 && c1 > 0) current = 0;
    signedDelta = -1;
  } else {
    current = -(2 / root) - c1 / c2;
    signedDelta = 1;
  }

  let delta = Number.POSITIVE_INFINITY;
  for (
    let index = 0;
    delta > motionConfig.press.newtonToleranceSeconds && index < 100;
    index += 1
  ) {
    const previous = current;
    current = iterateNewton(
      current,
      (time) => (c1 + c2 * time) * Math.exp(root * time) + signedDelta,
      (time) => (c2 * (root * time + 1) + c1 * root) * Math.exp(root * time),
    );
    delta = Math.abs(previous - current);
  }
  return current;
}

/** Ports Compose FloatSpringSpec duration estimation for its default 0.01 Float threshold. */
export function estimateSpringDurationMillis(
  from: number,
  to: number,
  initialVelocity: number,
  spec: SpringScaleSpec,
): number {
  if (from === to && initialVelocity === 0) return 0;
  const rawPosition = (from - to) / motionConfig.press.springVisibilityThreshold;
  const position = Math.abs(rawPosition);
  const velocity = (rawPosition < 0 ? -initialVelocity : initialVelocity) /
    motionConfig.press.springVisibilityThreshold;
  const naturalFrequency = Math.sqrt(spec.stiffness);
  let seconds: number;
  if (spec.dampingRatio < 1) {
    const realRoot = -spec.dampingRatio * naturalFrequency;
    const imaginaryRoot = naturalFrequency * Math.sqrt(1 - spec.dampingRatio ** 2);
    const c2 = (velocity - realRoot * position) / imaginaryRoot;
    const envelope = Math.sqrt(position ** 2 + c2 ** 2);
    seconds = Math.log(1 / envelope) / realRoot;
  } else {
    seconds = estimateCriticalDurationSeconds(-naturalFrequency, position, velocity);
  }
  return Math.max(0, Math.floor(seconds * 1_000));
}

export function createSpringScaleMotion(
  from: number,
  to: number,
  initialVelocity: number,
  spec: SpringScaleSpec,
): SpringScaleMotion {
  const durationMillis = estimateSpringDurationMillis(from, to, initialVelocity, spec);
  const sampleCount = Math.max(
    1,
    Math.ceil(durationMillis / motionConfig.press.springSampleIntervalMillis),
  );
  const keyframes = Array.from({ length: sampleCount + 1 }, (_, index) => {
    const offset = index / sampleCount;
    const scale = index === sampleCount
      ? to
      : springSample(from, to, initialVelocity, spec, durationMillis * offset).value;
    return { offset, scale };
  });
  return { kind: "spring", from, to, initialVelocity, durationMillis, spec, keyframes };
}

function cubicBezierCoordinate(time: number, first: number, second: number): number {
  const inverse = 1 - time;
  return 3 * inverse ** 2 * time * first + 3 * inverse * time ** 2 * second + time ** 3;
}

function cubicBezierDerivative(time: number, first: number, second: number): number {
  const inverse = 1 - time;
  return 3 * inverse ** 2 * first + 6 * inverse * time * (second - first) +
    3 * time ** 2 * (1 - second);
}

function cubicBezierProgress(
  fraction: number,
  easing: readonly [number, number, number, number],
): number {
  const [x1, y1, x2, y2] = easing;
  let parameter = fraction;
  for (let index = 0; index < 8; index += 1) {
    const error = cubicBezierCoordinate(parameter, x1, x2) - fraction;
    const slope = cubicBezierDerivative(parameter, x1, x2);
    if (Math.abs(error) < 1e-7 || Math.abs(slope) < 1e-7) break;
    parameter = Math.min(1, Math.max(0, parameter - error / slope));
  }
  return cubicBezierCoordinate(parameter, y1, y2);
}

export function createTweenScaleMotion(
  from: number,
  to: number,
  durationMillis: number,
  easing: readonly [number, number, number, number] = FAST_OUT_SLOW_IN,
): TweenScaleMotion {
  return {
    kind: "tween",
    from,
    to,
    durationMillis,
    easing,
    keyframes: [{ offset: 0, scale: from }, { offset: 1, scale: to }],
  };
}

export function sampleScaleMotion(motion: ScaleMotion, elapsedMillis: number): ScaleMotionSample {
  if (motion.durationMillis === 0 || elapsedMillis >= motion.durationMillis) {
    return { value: motion.to, velocity: 0 };
  }
  const elapsed = Math.max(0, elapsedMillis);
  if (motion.kind === "spring") {
    return springSample(
      motion.from,
      motion.to,
      motion.initialVelocity,
      motion.spec,
      elapsed,
    );
  }
  const fraction = elapsed / motion.durationMillis;
  const progress = cubicBezierProgress(fraction, motion.easing);
  return { value: motion.from + (motion.to - motion.from) * progress, velocity: 0 };
}
