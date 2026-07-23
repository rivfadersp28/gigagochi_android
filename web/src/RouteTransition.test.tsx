import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RouteTransition } from "./RouteTransition";
import {
  APP_ROOT_FADE_IN_DURATION_MILLIS,
  APP_ROOT_FADE_OUT_DURATION_MILLIS,
  ROUTE_FADE_IN_DURATION_MILLIS,
  ROUTE_FADE_OUT_DURATION_MILLIS,
  ROUTE_FADE_EASING,
  ROUTE_SLIDE_DURATION_MILLIS,
  ROUTE_TRANSITION_EASING,
  appRootTransitionSpec,
  appRouteDepth,
  dashboardOverlayTransitionSpec,
  isForwardRouteTransition,
  routeTransitionAnimationClass,
  routeTransitionSpec,
  type TransitionRoute,
} from "./routeTransitionModel";

function routeNode(route: TransitionRoute) {
  const label = route ?? "dashboard";
  return <section data-testid={`route-${label}`}>{label}</section>;
}

function layerFor(testId: string): HTMLElement {
  const layer = screen.getByTestId(testId).closest(".route-transition__layer");
  if (!(layer instanceof HTMLElement)) throw new Error(`Missing layer for ${testId}`);
  return layer;
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("native route transition model", () => {
  it("maps the exact Dashboard stack depths", () => {
    expect(appRouteDepth("dashboard")).toBe(0);
    expect(appRouteDepth("events")).toBe(1);
    expect(appRouteDepth("story")).toBe(2);
    expect(appRouteDepth("create")).toBe(0);
    expect(appRouteDepth("connectionError")).toBe(0);
    expect(appRouteDepth("localDataError")).toBe(0);
    expect(appRouteDepth(null)).toBe(0);
  });

  it("uses asymmetric 180/120 ms fades at the same depth", () => {
    const spec = routeTransitionSpec("connectionError", "localDataError", false);

    expect(spec.kind).toBe("fade");
    expect(spec.totalDurationMillis).toBe(ROUTE_FADE_IN_DURATION_MILLIS);
    expect(spec.enter).toEqual({
      durationMillis: 180,
      easing: ROUTE_FADE_EASING,
      from: { opacity: 0, translateXPercent: 0 },
      to: { opacity: 1, translateXPercent: 0 },
    });
    expect(spec.exit).toEqual({
      durationMillis: ROUTE_FADE_OUT_DURATION_MILLIS,
      easing: ROUTE_FADE_EASING,
      from: { opacity: 1, translateXPercent: 0 },
      to: { opacity: 0, translateXPercent: 0 },
    });
    expect(routeTransitionAnimationClass(spec, "enter")).toBe(
      "route-transition__layer--fade-enter",
    );
  });

  it("uses the outer Android 220/120 ms FastOutSlowIn fade for Create ↔ Dashboard", () => {
    const spec = appRootTransitionSpec("create", "dashboard", false);

    expect(spec.kind).toBe("fade");
    expect(spec.totalDurationMillis).toBe(APP_ROOT_FADE_IN_DURATION_MILLIS);
    expect(spec.enter).toEqual({
      durationMillis: 220,
      easing: ROUTE_FADE_EASING,
      from: { opacity: 0, translateXPercent: 0 },
      to: { opacity: 1, translateXPercent: 0 },
    });
    expect(spec.exit).toEqual({
      durationMillis: APP_ROOT_FADE_OUT_DURATION_MILLIS,
      easing: ROUTE_FADE_EASING,
      from: { opacity: 1, translateXPercent: 0 },
      to: { opacity: 0, translateXPercent: 0 },
    });
    expect(appRootTransitionSpec("dashboard", "create", true).kind).toBe("instant");
    expect(appRootTransitionSpec("dashboard", "dashboard", false).kind).toBe("instant");
  });

  it("matches the exact forward and backward parallax tracks", () => {
    const forward = routeTransitionSpec("dashboard", "events", false);
    expect(isForwardRouteTransition("dashboard", "events")).toBe(true);
    expect(forward.kind).toBe("slide");
    if (forward.kind !== "slide") throw new Error("Expected slide");
    expect(forward.direction).toBe("forward");
    expect(forward.totalDurationMillis).toBe(ROUTE_SLIDE_DURATION_MILLIS);
    expect(forward.enter).toMatchObject({
      durationMillis: 300,
      easing: ROUTE_TRANSITION_EASING,
      from: { opacity: 1, translateXPercent: 100 },
      to: { opacity: 1, translateXPercent: 0 },
    });
    expect(forward.exit.to.translateXPercent).toBe(-25);

    const backward = routeTransitionSpec("story", "events", false);
    expect(backward.kind).toBe("slide");
    if (backward.kind !== "slide") throw new Error("Expected slide");
    expect(backward.direction).toBe("backward");
    expect(backward.enter.from.translateXPercent).toBe(-25);
    expect(backward.exit.to.translateXPercent).toBe(100);
  });

  it("keeps root null states instant and normalizes only Dashboard overlays", () => {
    expect(routeTransitionSpec(null, "events", false).kind).toBe("instant");
    expect(routeTransitionSpec("events", null, false).kind).toBe("instant");
    const enterOverlay = dashboardOverlayTransitionSpec(null, "events", false);
    const leaveOverlay = dashboardOverlayTransitionSpec("events", null, false);
    expect(enterOverlay.kind === "slide" && enterOverlay.direction).toBe("forward");
    expect(leaveOverlay.kind === "slide" && leaveOverlay.direction).toBe("backward");
    expect(dashboardOverlayTransitionSpec(null, "dashboard", false).kind).toBe("instant");
    expect(routeTransitionSpec("dashboard", "story", true)).toMatchObject({
      kind: "instant",
      totalDurationMillis: 0,
    });
  });
});

describe("RouteTransition", () => {
  it("runs forward compositor layers and removes will-change after 300 ms", () => {
    vi.useFakeTimers();
    const view = render(
      <RouteTransition route="dashboard" reducedMotion={false} renderRoute={routeNode} />,
    );

    view.rerender(
      <RouteTransition route="events" reducedMotion={false} renderRoute={routeNode} />,
    );

    const oldDashboard = layerFor("route-dashboard");
    const newEvents = layerFor("route-events");
    expect(oldDashboard).toHaveClass("route-transition__layer--forward-exit");
    expect(newEvents).toHaveClass("route-transition__layer--forward-enter");
    expect(oldDashboard).toHaveClass("route-transition__layer--animating-slide");
    expect(newEvents).toHaveClass("route-transition__layer--animating-slide");
    expect(oldDashboard).toHaveAttribute("aria-hidden", "true");
    expect(oldDashboard).toHaveAttribute("inert");
    expect(newEvents).not.toHaveAttribute("aria-hidden");
    expect(newEvents).not.toHaveAttribute("inert");

    act(() => vi.advanceTimersByTime(299));
    expect(screen.getByTestId("route-dashboard")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByTestId("route-dashboard")).not.toBeInTheDocument();
    expect(layerFor("route-events")).not.toHaveClass("route-transition__layer--animating");
    expect(screen.getByLabelText("Переход между экранами")).toHaveAttribute(
      "data-transition-active",
      "false",
    );
  });

  it("keeps the outgoing same-depth route through the 180 ms fade-in", () => {
    vi.useFakeTimers();
    const view = render(
      <RouteTransition route="connectionError" reducedMotion={false} renderRoute={routeNode} />,
    );
    view.rerender(
      <RouteTransition route="localDataError" reducedMotion={false} renderRoute={routeNode} />,
    );

    expect(layerFor("route-connectionError")).toHaveClass("route-transition__layer--fade-exit");
    expect(layerFor("route-localDataError")).toHaveClass("route-transition__layer--fade-enter");
    expect(layerFor("route-localDataError")).toHaveClass("route-transition__layer--animating-fade");
    act(() => vi.advanceTimersByTime(179));
    expect(screen.getByTestId("route-connectionError")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByTestId("route-connectionError")).not.toBeInTheDocument();
  });

  it("replaces immediately when reduced motion is enabled", () => {
    const settled = vi.fn();
    const view = render(
      <RouteTransition
        route="dashboard"
        reducedMotion={false}
        renderRoute={routeNode}
        onTransitionSettled={settled}
      />,
    );
    view.rerender(
      <RouteTransition
        route="story"
        reducedMotion
        renderRoute={routeNode}
        onTransitionSettled={settled}
      />,
    );

    expect(screen.queryByTestId("route-dashboard")).not.toBeInTheDocument();
    expect(screen.getByTestId("route-story")).toBeInTheDocument();
    expect(layerFor("route-story")).not.toHaveClass("route-transition__layer--animating");
    expect(settled).toHaveBeenCalledWith("story", 1);
  });

  it("sequences interrupted System Back targets without stale routes or stale completion", () => {
    vi.useFakeTimers();
    const settled = vi.fn();
    const renderTransition = (route: TransitionRoute) => (
      <RouteTransition
        route={route}
        reducedMotion={false}
        renderRoute={routeNode}
        onTransitionSettled={settled}
      />
    );
    const view = render(renderTransition("dashboard"));

    view.rerender(renderTransition("events"));
    act(() => vi.advanceTimersByTime(100));
    view.rerender(renderTransition("story"));
    expect(screen.queryByTestId("route-dashboard")).not.toBeInTheDocument();
    expect(layerFor("route-events")).toHaveAttribute("data-transition-role", "outgoing");
    expect(layerFor("route-story")).toHaveAttribute("data-transition-role", "incoming");

    // Two Back targets arrive before either prior transition can settle.
    view.rerender(renderTransition("events"));
    expect(screen.queryByTestId("route-dashboard")).not.toBeInTheDocument();
    expect(screen.queryAllByTestId("route-events")).toHaveLength(1);
    expect(layerFor("route-story")).toHaveAttribute("data-transition-role", "outgoing");
    view.rerender(renderTransition("dashboard"));
    expect(screen.queryByTestId("route-story")).not.toBeInTheDocument();
    expect(layerFor("route-events")).toHaveAttribute("data-transition-role", "outgoing");
    expect(layerFor("route-dashboard")).toHaveAttribute("data-transition-role", "incoming");

    act(() => vi.advanceTimersByTime(299));
    expect(settled).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByTestId("route-events")).not.toBeInTheDocument();
    expect(screen.getByTestId("route-dashboard")).toBeInTheDocument();
    expect(settled).toHaveBeenCalledTimes(1);
    expect(settled).toHaveBeenCalledWith("dashboard", 4);
  });

  it("cleans up an interrupted root fade and focuses only the latest incoming main", () => {
    vi.useFakeTimers();
    const settled = vi.fn();
    const rootNode = (route: TransitionRoute) => (
      <main data-testid={`root-${route ?? "none"}`} tabIndex={-1}>
        {route}
      </main>
    );
    const renderTransition = (route: "create" | "dashboard") => (
      <RouteTransition
        route={route}
        reducedMotion={false}
        renderRoute={rootNode}
        transitionSpec={appRootTransitionSpec}
        focusInitial={false}
        onTransitionSettled={settled}
      />
    );
    const view = render(renderTransition("create"));
    expect(screen.getByTestId("root-create")).not.toHaveFocus();

    view.rerender(renderTransition("dashboard"));
    expect(screen.getByTestId("root-dashboard")).toHaveFocus();
    expect(layerFor("root-create")).toHaveAttribute("inert");
    expect(layerFor("root-dashboard")).not.toHaveAttribute("inert");

    act(() => vi.advanceTimersByTime(80));
    view.rerender(renderTransition("create"));
    expect(screen.queryByTestId("root-dashboard")).toBeInTheDocument();
    expect(screen.queryAllByTestId("root-create")).toHaveLength(1);
    expect(screen.getByTestId("root-create")).toHaveFocus();
    expect(layerFor("root-dashboard")).toHaveAttribute("inert");

    act(() => vi.advanceTimersByTime(219));
    expect(screen.getByTestId("root-dashboard")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByTestId("root-dashboard")).not.toBeInTheDocument();
    expect(screen.getByTestId("root-create")).toHaveFocus();
    expect(settled).toHaveBeenCalledTimes(1);
    expect(settled).toHaveBeenCalledWith("create", 2);
  });
});
