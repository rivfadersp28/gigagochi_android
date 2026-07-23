import { act, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  RetainedDashboardStack,
  type DashboardOverlayRoute,
  type DashboardStackRoute,
} from "./RetainedDashboardStack";

let dashboardMounts = 0;

function DashboardProbe({ active }: { active: boolean }) {
  const [mountId] = useState(() => ++dashboardMounts);
  return (
    <section
      data-testid="dashboard-probe"
      data-mount-id={mountId}
      data-active-prop={active ? "true" : "false"}
    >
      <video data-testid="dashboard-video" />
    </section>
  );
}

function overlay(route: DashboardOverlayRoute | null) {
  return route ? <section data-testid={`overlay-${route}`}>{route}</section> : null;
}

function stack(
  route: DashboardStackRoute,
  reducedMotion = false,
  settled?: (route: DashboardOverlayRoute | null, sequence: number) => void,
) {
  return (
    <RetainedDashboardStack
      route={route}
      reducedMotion={reducedMotion}
      renderDashboard={(active) => <DashboardProbe active={active} />}
      renderOverlay={overlay}
      onOverlayTransitionSettled={settled}
    />
  );
}

afterEach(() => {
  dashboardMounts = 0;
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("RetainedDashboardStack", () => {
  it("retains the exact Dashboard/video subtree under overlays and deactivates it", () => {
    vi.useFakeTimers();
    const view = render(stack("dashboard"));
    const dashboard = screen.getByTestId("dashboard-probe");
    const video = screen.getByTestId("dashboard-video");
    const dashboardLayer = dashboard.closest(".retained-dashboard-stack__dashboard");

    expect(dashboard).toHaveAttribute("data-mount-id", "1");
    expect(dashboard).toHaveAttribute("data-active-prop", "true");
    expect(dashboardLayer).toHaveAttribute("data-active", "true");
    expect(dashboardLayer).not.toHaveAttribute("aria-hidden");
    expect(dashboardLayer).not.toHaveAttribute("inert");

    view.rerender(stack("events"));
    expect(screen.getByTestId("dashboard-probe")).toBe(dashboard);
    expect(screen.getByTestId("dashboard-video")).toBe(video);
    expect(dashboardMounts).toBe(1);
    expect(dashboard).toHaveAttribute("data-active-prop", "false");
    expect(dashboardLayer).toHaveAttribute("data-active", "false");
    expect(dashboardLayer).toHaveAttribute("aria-hidden", "true");
    expect(dashboardLayer).toHaveAttribute("inert");
    expect(screen.getByTestId("overlay-events")).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(300));
    view.rerender(stack("story"));
    expect(screen.getByTestId("dashboard-probe")).toBe(dashboard);
    expect(screen.getByTestId("dashboard-video")).toBe(video);
    expect(dashboardMounts).toBe(1);
    expect(screen.getByTestId("overlay-story")).toBeInTheDocument();
  });

  it("reactivates Dashboard immediately while the backward overlay exits", () => {
    vi.useFakeTimers();
    const view = render(stack("events"));
    const dashboard = screen.getByTestId("dashboard-probe");
    const video = screen.getByTestId("dashboard-video");
    const dashboardLayer = dashboard.closest(".retained-dashboard-stack__dashboard");
    expect(dashboardLayer).toHaveAttribute("aria-hidden", "true");

    view.rerender(stack("dashboard"));
    expect(screen.getByTestId("dashboard-probe")).toBe(dashboard);
    expect(screen.getByTestId("dashboard-video")).toBe(video);
    expect(dashboard).toHaveAttribute("data-active-prop", "true");
    expect(dashboardLayer).toHaveAttribute("data-active", "true");
    expect(dashboardLayer).not.toHaveAttribute("aria-hidden");
    expect(dashboardLayer).not.toHaveAttribute("inert");
    expect(screen.getByTestId("overlay-events")).toBeInTheDocument();
    expect(screen.getByTestId("overlay-events").closest(".route-transition__layer")).toHaveClass(
      "route-transition__layer--backward-exit",
    );

    act(() => vi.advanceTimersByTime(300));
    expect(screen.queryByTestId("overlay-events")).not.toBeInTheDocument();
    expect(dashboardMounts).toBe(1);
  });

  it("handles rapid Story → Events → Dashboard Back sequencing with one retained Dashboard", () => {
    vi.useFakeTimers();
    const settled = vi.fn();
    const view = render(stack("story", false, settled));
    const dashboard = screen.getByTestId("dashboard-probe");

    view.rerender(stack("events", false, settled));
    expect(screen.getByTestId("overlay-story")).toBeInTheDocument();
    expect(screen.getByTestId("overlay-events")).toBeInTheDocument();
    view.rerender(stack("dashboard", false, settled));
    expect(screen.queryByTestId("overlay-story")).not.toBeInTheDocument();
    expect(screen.getByTestId("overlay-events")).toBeInTheDocument();
    expect(screen.getByTestId("dashboard-probe")).toBe(dashboard);
    expect(dashboard).toHaveAttribute("data-active-prop", "true");

    act(() => vi.advanceTimersByTime(300));
    expect(screen.queryByTestId("overlay-events")).not.toBeInTheDocument();
    expect(dashboardMounts).toBe(1);
    expect(settled).toHaveBeenCalledTimes(1);
    expect(settled).toHaveBeenCalledWith(null, 2);
  });

  it("switches overlays instantly under reduced motion without remounting Dashboard", () => {
    const view = render(stack("events", true));
    const dashboard = screen.getByTestId("dashboard-probe");
    const video = screen.getByTestId("dashboard-video");

    view.rerender(stack("story", true));
    expect(screen.queryByTestId("overlay-events")).not.toBeInTheDocument();
    expect(screen.getByTestId("overlay-story")).toBeInTheDocument();
    expect(screen.getByTestId("dashboard-probe")).toBe(dashboard);
    expect(screen.getByTestId("dashboard-video")).toBe(video);
    expect(dashboardMounts).toBe(1);
    expect(screen.getByLabelText("Переход панели приложения")).toHaveAttribute(
      "data-transition-active",
      "false",
    );
  });
});
