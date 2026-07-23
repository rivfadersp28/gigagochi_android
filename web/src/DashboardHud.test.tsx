import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DASHBOARD_STATUS_GLYPH_PATHS, StatusRing } from "./DashboardHud";

describe("dashboard native HUD", () => {
  it("uses the native long-form status paths and exact ring geometry", () => {
    const { container, rerender } = render(
      <StatusRing kind="hunger" label="Сытость" value={50} />,
    );
    const track = container.querySelector(".stat-ring__track");
    const value = container.querySelector(".stat-ring__value");
    const glyph = container.querySelector(".stat-ring__glyph");
    expect(track).toHaveAttribute("r", "23.5");
    expect(value).toHaveAttribute("stroke-dasharray", String(2 * Math.PI * 23.5));
    expect(glyph).toHaveAttribute("d", DASHBOARD_STATUS_GLYPH_PATHS.hunger);
    expect(DASHBOARD_STATUS_GLYPH_PATHS.hunger.length).toBeGreaterThan(1_000);

    rerender(<StatusRing kind="energy" label="Энергия" value={75} />);
    expect(container.querySelector(".stat-ring__glyph")).toHaveAttribute(
      "d",
      "M27 36.85L25.55 35.53C20.4 30.86 17 27.78 17 24C17 20.92 19.42 18.5 22.5 18.5C24.24 18.5 25.91 19.31 27 20.59C28.09 19.31 29.76 18.5 31.5 18.5C34.58 18.5 37 20.92 37 24C37 27.78 33.6 30.86 28.45 35.54L27 36.85Z",
    );
  });
});
