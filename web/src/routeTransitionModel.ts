import type { AppRoute } from "./contracts";
import { motionConfig } from "./motionConfig";

export type TransitionRoute = AppRoute | null;
export type RouteTransitionDirection = "forward" | "backward";
export type RouteTransitionRole = "enter" | "exit";

export interface RouteMotionFrame {
  opacity: number;
  translateXPercent: number;
}

export interface RouteMotionTrack {
  durationMillis: number;
  easing: string;
  from: RouteMotionFrame;
  to: RouteMotionFrame;
}

export type RouteTransitionSpec =
  | {
      kind: "instant";
      totalDurationMillis: number;
      enter: RouteMotionTrack;
      exit: RouteMotionTrack;
    }
  | {
      kind: "fade";
      totalDurationMillis: number;
      enter: RouteMotionTrack;
      exit: RouteMotionTrack;
    }
  | {
      kind: "slide";
      direction: RouteTransitionDirection;
      totalDurationMillis: number;
      enter: RouteMotionTrack;
      exit: RouteMotionTrack;
    };

export const ROUTE_SLIDE_DURATION_MILLIS = motionConfig.route.slideDurationMillis;
export const ROUTE_FADE_IN_DURATION_MILLIS = motionConfig.route.fadeInDurationMillis;
export const ROUTE_FADE_OUT_DURATION_MILLIS = motionConfig.route.fadeOutDurationMillis;
export const APP_ROOT_FADE_IN_DURATION_MILLIS = motionConfig.route.rootFadeInDurationMillis;
export const APP_ROOT_FADE_OUT_DURATION_MILLIS = motionConfig.route.rootFadeOutDurationMillis;
export const ROUTE_FADE_EASING = motionConfig.easing.fastOutSlowIn.css;
export const ROUTE_TRANSITION_EASING = motionConfig.easing.routeTransition.css;
const STILL_FRAME: RouteMotionFrame = { opacity: 1, translateXPercent: 0 };

export function appRouteDepth(route: TransitionRoute): 0 | 1 | 2 {
  switch (route) {
    case "events":
      return 1;
    case "story":
      return 2;
    case "create":
    case "dashboard":
    case "connectionError":
    case "localDataError":
    case null:
      return 0;
  }
}

export function isForwardRouteTransition(
  initial: TransitionRoute,
  target: TransitionRoute,
): boolean {
  return appRouteDepth(target) > appRouteDepth(initial);
}

function track(
  durationMillis: number,
  easing: string,
  from: RouteMotionFrame,
  to: RouteMotionFrame,
): RouteMotionTrack {
  return { durationMillis, easing, from, to };
}

export function routeTransitionSpec(
  initial: TransitionRoute,
  target: TransitionRoute,
  reducedMotion: boolean,
): RouteTransitionSpec {
  if (reducedMotion || initial === null || target === null || initial === target) {
    const still = track(
      motionConfig.reducedMotion.instantDurationMillis,
      motionConfig.easing.linear,
      STILL_FRAME,
      STILL_FRAME,
    );
    return {
      kind: "instant",
      totalDurationMillis: motionConfig.reducedMotion.instantDurationMillis,
      enter: still,
      exit: still,
    };
  }

  if (appRouteDepth(initial) === appRouteDepth(target)) {
    return {
      kind: "fade",
      totalDurationMillis: ROUTE_FADE_IN_DURATION_MILLIS,
      enter: track(
        ROUTE_FADE_IN_DURATION_MILLIS,
        ROUTE_FADE_EASING,
        { opacity: 0, translateXPercent: 0 },
        STILL_FRAME,
      ),
      exit: track(
        ROUTE_FADE_OUT_DURATION_MILLIS,
        ROUTE_FADE_EASING,
        STILL_FRAME,
        { opacity: 0, translateXPercent: 0 },
      ),
    };
  }

  const direction: RouteTransitionDirection = isForwardRouteTransition(initial, target)
    ? "forward"
    : "backward";
  return {
    kind: "slide",
    direction,
    totalDurationMillis: ROUTE_SLIDE_DURATION_MILLIS,
    enter: track(
      ROUTE_SLIDE_DURATION_MILLIS,
      ROUTE_TRANSITION_EASING,
      {
        opacity: 1,
        translateXPercent: direction === "forward"
          ? motionConfig.route.fullOffsetPercent
          : -motionConfig.route.parallaxOffsetPercent,
      },
      STILL_FRAME,
    ),
    exit: track(
      ROUTE_SLIDE_DURATION_MILLIS,
      ROUTE_TRANSITION_EASING,
      STILL_FRAME,
      {
        opacity: 1,
        translateXPercent: direction === "forward"
          ? -motionConfig.route.parallaxOffsetPercent
          : motionConfig.route.fullOffsetPercent,
      },
    ),
  };
}

/**
 * Mirrors MainActivity's outer AnimatedContent boundary. This boundary changes only when the
 * product moves between Create and the retained Dashboard stack; unlike same-depth route changes,
 * its incoming layer uses the Android-specific 220 ms fade.
 */
export function appRootTransitionSpec(
  initial: TransitionRoute,
  target: TransitionRoute,
  reducedMotion: boolean,
): RouteTransitionSpec {
  if (reducedMotion || initial === null || target === null || initial === target) {
    const still = track(
      motionConfig.reducedMotion.instantDurationMillis,
      motionConfig.easing.linear,
      STILL_FRAME,
      STILL_FRAME,
    );
    return {
      kind: "instant",
      totalDurationMillis: motionConfig.reducedMotion.instantDurationMillis,
      enter: still,
      exit: still,
    };
  }
  return {
    kind: "fade",
    totalDurationMillis: APP_ROOT_FADE_IN_DURATION_MILLIS,
    enter: track(
      APP_ROOT_FADE_IN_DURATION_MILLIS,
      ROUTE_FADE_EASING,
      { opacity: 0, translateXPercent: 0 },
      STILL_FRAME,
    ),
    exit: track(
      APP_ROOT_FADE_OUT_DURATION_MILLIS,
      ROUTE_FADE_EASING,
      STILL_FRAME,
      { opacity: 0, translateXPercent: 0 },
    ),
  };
}

export function dashboardOverlayTransitionSpec(
  initial: TransitionRoute,
  target: TransitionRoute,
  reducedMotion: boolean,
): RouteTransitionSpec {
  return routeTransitionSpec(
    initial ?? "dashboard",
    target ?? "dashboard",
    reducedMotion,
  );
}

export function routeTransitionAnimationClass(
  spec: RouteTransitionSpec,
  role: RouteTransitionRole,
): string | null {
  if (spec.kind === "instant") return null;
  if (spec.kind === "fade") return `route-transition__layer--fade-${role}`;
  return `route-transition__layer--${spec.direction}-${role}`;
}

export function transitionRouteLabel(route: TransitionRoute): string {
  return route ?? "none";
}
