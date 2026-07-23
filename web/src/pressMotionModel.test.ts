import { describe, expect, it } from "vitest";
import {
  createSpringScaleMotion,
  createTweenScaleMotion,
  DASHBOARD_PRESS_SPRING,
  DASHBOARD_RELEASE_SPRING,
  EVENT_PRESS_SPRING,
  EVENT_RELEASE_SPRING,
  FAST_OUT_SLOW_IN,
  sampleScaleMotion,
  STORY_PRESS_DURATION_MILLIS,
} from "./pressMotionModel";
import { motionConfig } from "./motionConfig";

describe("native press motion", () => {
  it("locks the Dashboard spring to the Android Animatable contract", () => {
    expect(DASHBOARD_PRESS_SPRING).toEqual({ dampingRatio: 1, stiffness: 10_000 });
    expect(DASHBOARD_RELEASE_SPRING).toEqual({ dampingRatio: 0.55, stiffness: 1_500 });
    expect(createSpringScaleMotion(
      motionConfig.press.dashboardPressedScale,
      1,
      0,
      DASHBOARD_RELEASE_SPRING,
    ).durationMillis).toBe(motionConfig.press.dashboardCanonicalSettleMillis);
    const press = createSpringScaleMotion(1, 0.92, 0, DASHBOARD_PRESS_SPRING);
    const interrupted = sampleScaleMotion(press, Math.min(12, press.durationMillis - 1));
    const release = createSpringScaleMotion(
      interrupted.value,
      1,
      interrupted.velocity,
      DASHBOARD_RELEASE_SPRING,
    );

    expect(press.durationMillis).toBeGreaterThan(0);
    expect(release.durationMillis).toBeGreaterThan(0);
    expect(release.keyframes[0].scale).toBeCloseTo(interrupted.value, 8);
    expect(sampleScaleMotion(release, 0).velocity).toBeCloseTo(interrupted.velocity, 12);
    expect(release.keyframes.at(-1)).toEqual({ offset: 1, scale: 1 });
  });

  it("reverses the Event spring from its exact interrupted value and velocity", () => {
    expect(EVENT_PRESS_SPRING).toEqual({ dampingRatio: 1, stiffness: 10_000 });
    expect(EVENT_RELEASE_SPRING).toEqual({ dampingRatio: 0.6, stiffness: 1_500 });
    expect(createSpringScaleMotion(
      motionConfig.press.eventPressedScale,
      1,
      0,
      EVENT_RELEASE_SPRING,
    ).durationMillis).toBe(motionConfig.press.eventCanonicalSettleMillis);
    const press = createSpringScaleMotion(1, 0.94, 0, EVENT_PRESS_SPRING);
    const interrupted = sampleScaleMotion(press, Math.min(12, press.durationMillis - 1));
    const release = createSpringScaleMotion(
      interrupted.value,
      1,
      interrupted.velocity,
      EVENT_RELEASE_SPRING,
    );

    expect(press.durationMillis).toBeGreaterThan(0);
    expect(release.durationMillis).toBeGreaterThan(0);
    expect(release.keyframes[0].scale).toBeCloseTo(interrupted.value, 8);
    expect(sampleScaleMotion(release, 0).value).toBeCloseTo(interrupted.value, 12);
    expect(sampleScaleMotion(release, 0).velocity).toBeCloseTo(interrupted.velocity, 12);
    expect(release.keyframes.at(-1)).toEqual({ offset: 1, scale: 1 });
  });

  it("reverses the Story tween from its current FastOutSlowIn scale", () => {
    const press = createTweenScaleMotion(
      1,
      0.9,
      STORY_PRESS_DURATION_MILLIS,
      FAST_OUT_SLOW_IN,
    );
    const interrupted = sampleScaleMotion(press, 70);
    const release = createTweenScaleMotion(
      interrupted.value,
      1,
      STORY_PRESS_DURATION_MILLIS,
      FAST_OUT_SLOW_IN,
    );

    expect(interrupted.value).toBeLessThan(1);
    expect(interrupted.value).toBeGreaterThan(0.9);
    expect(release.durationMillis).toBe(140);
    expect(release.keyframes[0].scale).toBe(interrupted.value);
    expect(release.easing).toEqual([0.4, 0, 0.2, 1]);
  });
});
