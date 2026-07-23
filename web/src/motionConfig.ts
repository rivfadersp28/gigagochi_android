export type CubicBezierCurve = readonly [number, number, number, number];

export interface CubicBezierEasing {
  readonly curve: CubicBezierCurve;
  readonly css: string;
}

export interface SpringMotionSpec {
  readonly dampingRatio: number;
  readonly stiffness: number;
}

export interface PetTapBulgeMotion {
  readonly durationMillis: number;
  readonly attackMillis: number;
  readonly holdUntilMillis: number;
  readonly releaseMillis: number;
  readonly strength: number;
}

export type CreationMotionPhase = "initial" | "transition" | "formed";

export interface CreationFrameRange {
  readonly startFrame: number;
  readonly endFrame: number;
  readonly loop: boolean;
}

export interface CreationMosaicFrame {
  readonly src: string;
  readonly edgePixels: number;
}

export interface MotionConfig {
  readonly easing: {
    readonly linear: "linear";
    readonly fastOutSlowIn: CubicBezierEasing;
    readonly routeTransition: CubicBezierEasing;
    readonly contentEntrance: CubicBezierEasing;
    readonly glyphReveal: CubicBezierEasing;
    readonly playfulEntrance: CubicBezierEasing;
  };
  readonly shared: {
    readonly minimumThinkingDurationMillis: number;
    readonly thinkingFrameIntervalMillis: number;
  };
  readonly media: {
    readonly dashboardPosterFadeDurationMillis: number;
    readonly storyPosterFadeDurationMillis: number;
  };
  readonly creation: {
    readonly timeline: {
      readonly framesPerSecond: number;
      readonly segments: Readonly<Record<CreationMotionPhase, CreationFrameRange>>;
    };
    readonly mosaic: {
      readonly frames: readonly CreationMosaicFrame[];
      readonly canvasSizePixels: number;
      readonly frameDurationMillis: number;
      readonly crossfadeDurationMillis: number;
    };
    readonly optionEntranceDurationMillis: number;
    readonly optionEntranceStaggerMillis: number;
    readonly optionEntranceScale: number;
    readonly optionPressedScale: number;
    readonly optionPressDurationMillis: number;
    readonly finalEntranceDurationMillis: number;
    readonly finalEntranceScale: number;
    readonly finishDelayMillis: number;
  };
  readonly dialogue: {
    readonly layerEntranceDurationMillis: number;
    readonly layerEntranceScale: number;
    readonly inputEntranceDurationMillis: number;
    readonly autoAdvanceDelayMillis: number;
    readonly unitDurationMillis: number;
    readonly unitStaggerMillis: number;
    readonly tailUnitDurationMillis: number;
    readonly maxAnimatedUnits: number;
    readonly speechIntervalMillis: number;
    readonly speechIntervalJitterMillis: number;
  };
  readonly onboarding: {
    readonly actionEntranceDurationMillis: number;
    readonly actionEntranceScale: number;
    readonly choiceEntranceStaggerMillis: number;
  };
  readonly route: {
    readonly slideDurationMillis: number;
    readonly fadeInDurationMillis: number;
    readonly fadeOutDurationMillis: number;
    readonly rootFadeInDurationMillis: number;
    readonly rootFadeOutDurationMillis: number;
    readonly fullOffsetPercent: number;
    readonly parallaxOffsetPercent: number;
  };
  readonly press: {
    readonly dashboardPressedScale: number;
    readonly eventPressedScale: number;
    readonly storyPressedScale: number;
    readonly dashboardPressSpring: SpringMotionSpec;
    readonly dashboardReleaseSpring: SpringMotionSpec;
    readonly eventPressSpring: SpringMotionSpec;
    readonly eventReleaseSpring: SpringMotionSpec;
    readonly storyDurationMillis: number;
    readonly dashboardCanonicalSettleMillis: number;
    readonly eventCanonicalSettleMillis: number;
    readonly springVisibilityThreshold: number;
    readonly springSampleIntervalMillis: number;
    readonly newtonToleranceSeconds: number;
  };
  readonly petTap: {
    readonly touchSlopCssPixels: number;
    readonly bulge: PetTapBulgeMotion;
    readonly thanksDurationMillis: number;
    readonly hearts: {
      readonly particleCount: number;
      readonly seedMultiplier: number;
      readonly seedOffset: number;
      readonly maxActiveBursts: number;
      readonly burstIntervalMillis: number;
      readonly lifetimeMillis: number;
      readonly fadeMillis: number;
    };
  };
  readonly dashboard: {
    readonly overlayEntranceDurationMillis: number;
    readonly overlayExitDurationMillis: number;
    readonly feedConsumeDurationMillis: number;
    readonly feedReappearDurationMillis: number;
    readonly feedPulseDurationMillis: number;
    readonly feedDragActivationDistance: number;
    readonly feedDropTolerance: number;
    readonly feedDragScale: number;
    readonly feedConsumeStartScale: number;
    readonly feedConsumeEndScale: number;
    readonly feedReappearStartScale: number;
    readonly feedReappearEndScale: number;
    readonly feedPulseStartScale: number;
    readonly feedPulseEndScale: number;
    readonly feedReducedPulseScale: number;
    readonly feedReducedConsumeScale: number;
    readonly feedReducedReappearScale: number;
  };
  readonly story: {
    readonly glyphRiseDurationMillis: number;
    readonly glyphStaggerMillis: number;
    readonly glyphRiseDistancePixels: number;
    readonly rewardEntranceDurationMillis: number;
    readonly answerEntranceDurationMillis: number;
    readonly answerEntranceStaggerMillis: number;
    readonly answerEntranceScale: number;
  };
  readonly ime: {
    readonly progressMin: number;
    readonly progressMax: number;
    readonly mediaShiftMaxPixels: number;
    readonly dialogueShiftMaxPixels: number;
  };
  readonly reducedMotion: {
    readonly instantDurationMillis: 0;
    readonly cssDurationMillis: number;
    readonly petTapBulge: PetTapBulgeMotion;
  };
}

export const motionConfig = {
  easing: {
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
  },
  shared: {
    minimumThinkingDurationMillis: 1_000,
    thinkingFrameIntervalMillis: 200,
  },
  media: {
    dashboardPosterFadeDurationMillis: 120,
    storyPosterFadeDurationMillis: 180,
  },
  creation: {
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
  },
  dialogue: {
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
  },
  onboarding: {
    actionEntranceDurationMillis: 300,
    actionEntranceScale: 0.6,
    choiceEntranceStaggerMillis: 200,
  },
  route: {
    slideDurationMillis: 300,
    fadeInDurationMillis: 180,
    fadeOutDurationMillis: 120,
    rootFadeInDurationMillis: 220,
    rootFadeOutDurationMillis: 120,
    fullOffsetPercent: 100,
    parallaxOffsetPercent: 25,
  },
  press: {
    dashboardPressedScale: 0.92,
    eventPressedScale: 0.94,
    storyPressedScale: 0.9,
    dashboardPressSpring: { dampingRatio: 1, stiffness: 10_000 },
    dashboardReleaseSpring: { dampingRatio: 0.55, stiffness: 1_500 },
    eventPressSpring: { dampingRatio: 1, stiffness: 10_000 },
    eventReleaseSpring: { dampingRatio: 0.6, stiffness: 1_500 },
    storyDurationMillis: 140,
    // Zero-velocity target→rest references. Interrupted springs continue to derive their
    // duration from the current analytical value and velocity.
    dashboardCanonicalSettleMillis: 106,
    eventCanonicalSettleMillis: 86,
    springVisibilityThreshold: 0.01,
    springSampleIntervalMillis: 4,
    newtonToleranceSeconds: 0.001,
  },
  petTap: {
    // Android's ViewConfiguration touch slop is 8 dp on the target devices;
    // WebView CSS pixels map to dp before the reference-plane transform.
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
  },
  dashboard: {
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
  },
  story: {
    glyphRiseDurationMillis: 300,
    glyphStaggerMillis: 12,
    glyphRiseDistancePixels: 12,
    rewardEntranceDurationMillis: 300,
    answerEntranceDurationMillis: 300,
    answerEntranceStaggerMillis: 200,
    answerEntranceScale: 0.6,
  },
  ime: {
    progressMin: 0,
    progressMax: 1,
    mediaShiftMaxPixels: 240,
    dialogueShiftMaxPixels: 292,
  },
  reducedMotion: {
    instantDurationMillis: 0,
    cssDurationMillis: 0.01,
    petTapBulge: {
      durationMillis: 100,
      attackMillis: 35,
      holdUntilMillis: 60,
      releaseMillis: 40,
      strength: 0.08,
    },
  },
} as const satisfies MotionConfig;

export type MotionCssVariableName = `--motion-${string}`;

function millis(value: number): string {
  return `${value}ms`;
}

export const motionCssVariables = {
  "--motion-ease-linear": motionConfig.easing.linear,
  "--motion-ease-fast-out-slow-in": motionConfig.easing.fastOutSlowIn.css,
  "--motion-ease-route": motionConfig.easing.routeTransition.css,
  "--motion-ease-enter": motionConfig.easing.contentEntrance.css,
  "--motion-ease-glyph": motionConfig.easing.glyphReveal.css,
  "--motion-ease-playful": motionConfig.easing.playfulEntrance.css,
  "--motion-dashboard-poster-fade-duration":
    millis(motionConfig.media.dashboardPosterFadeDurationMillis),
  "--motion-story-poster-fade-duration":
    millis(motionConfig.media.storyPosterFadeDurationMillis),
  "--motion-create-option-entrance-duration":
    millis(motionConfig.creation.optionEntranceDurationMillis),
  "--motion-create-option-press-duration":
    millis(motionConfig.creation.optionPressDurationMillis),
  "--motion-create-option-entrance-scale": String(motionConfig.creation.optionEntranceScale),
  "--motion-create-option-pressed-scale": String(motionConfig.creation.optionPressedScale),
  "--motion-create-final-entrance-duration":
    millis(motionConfig.creation.finalEntranceDurationMillis),
  "--motion-create-final-entrance-scale": String(motionConfig.creation.finalEntranceScale),
  "--motion-dialogue-layer-entrance-duration":
    millis(motionConfig.dialogue.layerEntranceDurationMillis),
  "--motion-dialogue-layer-entrance-scale": String(motionConfig.dialogue.layerEntranceScale),
  "--motion-dialogue-tail-unit-duration": millis(motionConfig.dialogue.tailUnitDurationMillis),
  "--motion-dashboard-input-entrance-duration":
    millis(motionConfig.dialogue.inputEntranceDurationMillis),
  "--motion-onboarding-action-entrance-duration":
    millis(motionConfig.onboarding.actionEntranceDurationMillis),
  "--motion-onboarding-action-entrance-scale":
    String(motionConfig.onboarding.actionEntranceScale),
  "--motion-dashboard-action-pressed-scale": String(motionConfig.press.dashboardPressedScale),
  "--motion-feed-consume-duration": millis(motionConfig.dashboard.feedConsumeDurationMillis),
  "--motion-feed-reappear-duration": millis(motionConfig.dashboard.feedReappearDurationMillis),
  "--motion-feed-pulse-duration": millis(motionConfig.dashboard.feedPulseDurationMillis),
  "--motion-feed-drag-scale": String(motionConfig.dashboard.feedDragScale),
  "--motion-feed-consume-start-scale": String(motionConfig.dashboard.feedConsumeStartScale),
  "--motion-feed-consume-end-scale": String(motionConfig.dashboard.feedConsumeEndScale),
  "--motion-feed-reappear-start-scale": String(motionConfig.dashboard.feedReappearStartScale),
  "--motion-feed-reappear-end-scale": String(motionConfig.dashboard.feedReappearEndScale),
  "--motion-feed-pulse-start-scale": String(motionConfig.dashboard.feedPulseStartScale),
  "--motion-feed-pulse-end-scale": String(motionConfig.dashboard.feedPulseEndScale),
  "--motion-feed-reduced-pulse-scale": String(motionConfig.dashboard.feedReducedPulseScale),
  "--motion-feed-reduced-consume-scale": String(motionConfig.dashboard.feedReducedConsumeScale),
  "--motion-feed-reduced-reappear-scale":
    String(motionConfig.dashboard.feedReducedReappearScale),
  "--motion-story-glyph-rise-distance": `${motionConfig.story.glyphRiseDistancePixels}px`,
  "--motion-story-glyph-rise-duration": millis(motionConfig.story.glyphRiseDurationMillis),
  "--motion-story-reward-entrance-duration":
    millis(motionConfig.story.rewardEntranceDurationMillis),
  "--motion-story-answer-entrance-duration":
    millis(motionConfig.story.answerEntranceDurationMillis),
  "--motion-story-answer-entrance-scale": String(motionConfig.story.answerEntranceScale),
  "--motion-reduced-duration": millis(motionConfig.reducedMotion.cssDurationMillis),
} as const satisfies Readonly<Record<MotionCssVariableName, string>>;

export interface ImeMotionProjection {
  readonly progress: number;
  readonly mediaShiftPixels: number;
  readonly dialogueShiftPixels: number;
}

export function projectImeMotion(progress: number): ImeMotionProjection {
  const boundedProgress = Number.isFinite(progress)
    ? Math.min(motionConfig.ime.progressMax, Math.max(motionConfig.ime.progressMin, progress))
    : motionConfig.ime.progressMin;
  return {
    progress: boundedProgress,
    mediaShiftPixels: -motionConfig.ime.mediaShiftMaxPixels * boundedProgress,
    dialogueShiftPixels: motionConfig.ime.dialogueShiftMaxPixels * boundedProgress,
  };
}
