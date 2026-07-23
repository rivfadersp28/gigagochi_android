import { useLayoutEffect, useRef, type ReactNode } from "react";
import { RouteTransition } from "./RouteTransition";
import type { AppRoute } from "./contracts";
import type { TransitionRoute } from "./routeTransitionModel";
import "./routeTransition.css";

export type DashboardStackRoute = Extract<
  AppRoute,
  "dashboard" | "events" | "story"
>;
export type DashboardOverlayRoute = Exclude<DashboardStackRoute, "dashboard">;

export interface RetainedDashboardStackProps {
  route: DashboardStackRoute;
  reducedMotion: boolean;
  renderDashboard(active: boolean): ReactNode;
  renderOverlay(route: DashboardOverlayRoute | null): ReactNode;
  onOverlayTransitionSettled?(
    route: DashboardOverlayRoute | null,
    sequence: number,
  ): void;
  className?: string;
}

function overlayRouteFromTransition(route: TransitionRoute): DashboardOverlayRoute | null {
  switch (route) {
    case "events":
    case "story":
      return route;
    case "dashboard":
    case "create":
    case "connectionError":
    case "localDataError":
    case null:
      return null;
  }
}

function isOverlayPresent(route: TransitionRoute): boolean {
  return overlayRouteFromTransition(route) !== null;
}

export function RetainedDashboardStack({
  route,
  reducedMotion,
  renderDashboard,
  renderOverlay,
  onOverlayTransitionSettled,
  className,
}: RetainedDashboardStackProps) {
  const dashboardActive = route === "dashboard";
  const lastDashboardFocusRef = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => {
    if (!dashboardActive) return;
    const target = lastDashboardFocusRef.current;
    if (target?.isConnected) target.focus({ preventScroll: true });
  }, [dashboardActive]);

  return (
    <section
      className={["retained-dashboard-stack", className].filter(Boolean).join(" ")}
      data-route={route}
    >
      <div
        className="retained-dashboard-stack__dashboard"
        data-active={dashboardActive ? "true" : "false"}
        aria-hidden={dashboardActive ? undefined : true}
        inert={dashboardActive ? undefined : true}
        onFocusCapture={(event) => {
          if (event.target instanceof HTMLElement) {
            lastDashboardFocusRef.current = event.target;
          }
        }}
      >
        {renderDashboard(dashboardActive)}
      </div>
      <RouteTransition
        route={route}
        reducedMotion={reducedMotion}
        isRoutePresent={isOverlayPresent}
        label="Переход панели приложения"
        className="retained-dashboard-stack__overlays"
        renderRoute={(target) => renderOverlay(overlayRouteFromTransition(target))}
        onTransitionSettled={(target, sequence) => {
          onOverlayTransitionSettled?.(
            overlayRouteFromTransition(target),
            sequence,
          );
        }}
      />
    </section>
  );
}
