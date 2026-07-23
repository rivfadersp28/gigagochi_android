import { describe, expect, it } from "vitest";
import appSource from "./App.tsx?raw";
import creationMosaicSource from "./CreationMosaic.tsx?raw";
import petTapHeartSource from "./PetTapHeartCanvas.tsx?raw";
import segmentedCreationSource from "./SegmentedCreationMedia.tsx?raw";
import eventStoryStyles from "./eventStory.css?raw";
import {
  motionConfig,
  motionCssVariables,
  projectImeMotion,
  type MotionConfig,
} from "./motionConfig";
import routeTransitionStyles from "./routeTransition.css?raw";
import styles from "./styles.css?raw";

const allMotionCss = `${styles}\n${eventStoryStyles}\n${routeTransitionStyles}`;

describe("canonical web motion config", () => {
  it("owns every normative duration, delay, stagger, frame, and IME bound", () => {
    expect(motionConfig.shared).toEqual({
      minimumThinkingDurationMillis: 1_000,
      thinkingFrameIntervalMillis: 200,
    });
    expect(motionConfig.media).toEqual({
      dashboardPosterFadeDurationMillis: 120,
      storyPosterFadeDurationMillis: 180,
    });
    expect(motionConfig.creation).toEqual({
      timeline: {
        framesPerSecond: 24,
        segments: {
          initial: { startFrame: 0, endFrame: 170, loop: true },
          transition: { startFrame: 170, endFrame: 267, loop: false },
          formed: { startFrame: 267, endFrame: 447, loop: true },
        },
      },
      mosaic: {
        frames: [
          { src: "/res/main_pet.png", edgePixels: 18 },
          { src: "/res/pet.png", edgePixels: 24 },
          { src: "/res/main_pet.png", edgePixels: 21 },
        ],
        canvasSizePixels: 280,
        frameDurationMillis: 720,
        crossfadeDurationMillis: 260,
      },
      optionEntranceDurationMillis: 300,
      optionEntranceStaggerMillis: 80,
      optionEntranceScale: 0.6,
      optionPressedScale: 0.9,
      optionPressDurationMillis: 140,
      finalEntranceDurationMillis: 300,
      finalEntranceScale: 0.8,
      finishDelayMillis: 900,
    });
    expect(motionConfig.dialogue).toEqual({
      layerEntranceDurationMillis: 300,
      layerEntranceScale: 1.035,
      inputEntranceDurationMillis: 220,
      autoAdvanceDelayMillis: 6_000,
      unitDurationMillis: 700,
      unitStaggerMillis: 24,
      tailUnitDurationMillis: 1,
      maxAnimatedUnits: 80,
      speechIntervalMillis: 48,
      speechIntervalJitterMillis: 6,
    });
    expect(motionConfig.onboarding).toEqual({
      actionEntranceDurationMillis: 300,
      actionEntranceScale: 0.6,
      choiceEntranceStaggerMillis: 200,
    });
    expect(motionConfig.route).toEqual({
      slideDurationMillis: 300,
      fadeInDurationMillis: 180,
      fadeOutDurationMillis: 120,
      rootFadeInDurationMillis: 220,
      rootFadeOutDurationMillis: 120,
      fullOffsetPercent: 100,
      parallaxOffsetPercent: 25,
    });
    expect(motionConfig.dashboard).toEqual({
      overlayEntranceDurationMillis: 220,
      overlayExitDurationMillis: 120,
      feedConsumeDurationMillis: 180,
      feedReappearDurationMillis: 220,
      feedPulseDurationMillis: 200,
      feedDragActivationDistance: 4,
      feedDropTolerance: 18,
      feedDragScale: 1.18,
      feedConsumeStartScale: 1,
      feedConsumeEndScale: 0,
      feedReappearStartScale: 0.7,
      feedReappearEndScale: 1,
      feedPulseStartScale: 0.72,
      feedPulseEndScale: 1.18,
      feedReducedPulseScale: 0.9132,
      feedReducedConsumeScale: 0.52,
      feedReducedReappearScale: 0.78,
    });
    expect(motionConfig.story).toEqual({
      glyphRiseDurationMillis: 300,
      glyphStaggerMillis: 12,
      glyphRiseDistancePixels: 12,
      rewardEntranceDurationMillis: 300,
      answerEntranceDurationMillis: 300,
      answerEntranceStaggerMillis: 200,
      answerEntranceScale: 0.6,
    });
    expect(motionConfig.ime).toEqual({
      progressMin: 0,
      progressMax: 1,
      mediaShiftMaxPixels: 240,
      dialogueShiftMaxPixels: 292,
    });
  });

  it("locks exact easing curves, springs, canonical settles, bulge, and particles", () => {
    expect(motionConfig.easing).toEqual({
      linear: "linear",
      fastOutSlowIn: {
        curve: [0.4, 0, 0.2, 1],
        css: "cubic-bezier(0.4, 0, 0.2, 1)",
      },
      routeTransition: {
        curve: [0.2, 0, 0, 1],
        css: "cubic-bezier(0.2, 0, 0, 1)",
      },
      contentEntrance: {
        curve: [0.16, 1, 0.3, 1],
        css: "cubic-bezier(0.16, 1, 0.3, 1)",
      },
      glyphReveal: {
        curve: [0.2, 0.8, 0.2, 1],
        css: "cubic-bezier(0.2, 0.8, 0.2, 1)",
      },
      playfulEntrance: {
        curve: [0.34, 1.56, 0.64, 1],
        css: "cubic-bezier(0.34, 1.56, 0.64, 1)",
      },
    });
    expect(motionConfig.press).toEqual({
      dashboardPressedScale: 0.92,
      eventPressedScale: 0.94,
      storyPressedScale: 0.9,
      dashboardPressSpring: { dampingRatio: 1, stiffness: 10_000 },
      dashboardReleaseSpring: { dampingRatio: 0.55, stiffness: 1_500 },
      eventPressSpring: { dampingRatio: 1, stiffness: 10_000 },
      eventReleaseSpring: { dampingRatio: 0.6, stiffness: 1_500 },
      storyDurationMillis: 140,
      dashboardCanonicalSettleMillis: 106,
      eventCanonicalSettleMillis: 86,
      springVisibilityThreshold: 0.01,
      springSampleIntervalMillis: 4,
      newtonToleranceSeconds: 0.001,
    });
    expect(motionConfig.petTap).toEqual({
      touchSlopCssPixels: 8,
      bulge: {
        durationMillis: 250,
        attackMillis: 120,
        holdUntilMillis: 170,
        releaseMillis: 80,
        strength: 0.18,
      },
      thanksDurationMillis: 5_000,
      hearts: {
        particleCount: 9,
        seedMultiplier: 7_919,
        seedOffset: 17,
        maxActiveBursts: 2,
        burstIntervalMillis: 80,
        lifetimeMillis: 1_889,
        fadeMillis: 120,
      },
    });
  });

  it("keeps reduced motion instant except for the bounded 100 ms pet bulge", () => {
    expect(motionConfig.reducedMotion).toEqual({
      instantDurationMillis: 0,
      cssDurationMillis: 0.01,
      petTapBulge: {
        durationMillis: 100,
        attackMillis: 35,
        holdUntilMillis: 60,
        releaseMillis: 40,
        strength: 0.08,
      },
    });

    expect(motionConfig.petTap.bulge.holdUntilMillis +
      motionConfig.petTap.bulge.releaseMillis).toBe(
      motionConfig.petTap.bulge.durationMillis,
    );
    expect(motionConfig.reducedMotion.petTapBulge.holdUntilMillis +
      motionConfig.reducedMotion.petTapBulge.releaseMillis).toBe(
      motionConfig.reducedMotion.petTapBulge.durationMillis,
    );
    const typedConfig: MotionConfig = motionConfig;
    expect(typedConfig.reducedMotion.instantDurationMillis).toBe(0);
  });

  it("projects IME motion through the single bounded interpolation contract", () => {
    expect(projectImeMotion(0)).toEqual({
      progress: 0,
      mediaShiftPixels: -0,
      dialogueShiftPixels: 0,
    });
    expect(projectImeMotion(0.5)).toEqual({
      progress: 0.5,
      mediaShiftPixels: -120,
      dialogueShiftPixels: 146,
    });
    expect(projectImeMotion(2)).toEqual({
      progress: 1,
      mediaShiftPixels: -240,
      dialogueShiftPixels: 292,
    });
    expect(projectImeMotion(Number.NaN)).toEqual({
      progress: 0,
      mediaShiftPixels: -0,
      dialogueShiftPixels: 0,
    });
  });

  it("bridges every CSS motion token from the typed config without CSS literals", () => {
    const referencedVariables = new Set(
      [...allMotionCss.matchAll(/var\((--motion-[a-z0-9-]+)/g)]
        .map((match) => match[1]),
    );
    expect(referencedVariables).toEqual(new Set(Object.keys(motionCssVariables)));
    expect(allMotionCss).not.toMatch(/\b\d+(?:\.\d+)?ms\b/);
    expect(allMotionCss).not.toContain("cubic-bezier(");
  });

  it("resolves dialogue motion aliases on the element that owns typed variables", () => {
    const rootBlock = styles.match(/:root\s*\{[\s\S]*?\n\}/)?.[0] ?? "";
    const appBlock = styles.match(/\.app\s*\{[\s\S]*?\n\}/)?.[0] ?? "";

    expect(rootBlock).not.toMatch(/--(?:route|enter|playful|glyph)-ease:/);
    expect(appBlock).toContain("--enter-ease: var(--motion-ease-enter)");
    expect(appBlock).toContain("--glyph-ease: var(--motion-ease-glyph)");
  });

  it("keeps high-risk cross-source motion values config-owned", () => {
    expect(segmentedCreationSource).toContain("motionConfig.creation.timeline");
    expect(segmentedCreationSource).not.toMatch(/\b(?:170|267|447)\s*\/\s*24\b/);
    expect(creationMosaicSource).toContain("motionConfig.creation.mosaic.frames");
    expect(creationMosaicSource).not.toMatch(/edge(?:Pixels)?:\s*(?:18|21|24)\b/);
    expect(appSource).toContain("projectImeMotion");
    expect(appSource).not.toMatch(/\b(?:240|292)\s*\*\s*snapshot\.safeArea\.imeProgress/);
    expect(petTapHeartSource).toContain("motionConfig.petTap.hearts");
    expect(petTapHeartSource).not.toMatch(
      /const (?:PARTICLE_COUNT|MAX_ACTIVE_BURSTS)|burstId\s*\*\s*7_919\s*\+\s*17/,
    );
  });
});
