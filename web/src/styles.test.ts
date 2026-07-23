import { describe, expect, it } from "vitest";
import eventStoryStyles from "./eventStory.css?raw";
import stylesheet from "./styles.css?raw";

describe("Android visual safety invariants", () => {
  it("keeps the Create generation retry above the native bottom safe area", () => {
    expect(stylesheet).toMatch(
      /\.creation-generation__retry\s*\{[^}]*top:\s*calc\(809px - var\(--safe-bottom\)\);/s,
    );
  });

  it("preserves the authored send icon colors for an Outfit retry", () => {
    expect(stylesheet).toMatch(
      /\.dashboard-input--outfit label > button\[data-operation-action="retry"\] img\s*\{[^}]*filter:\s*none;/s,
    );
  });

  it("keeps the dashboard placeholder at native maxLines one with clip overflow", () => {
    expect(stylesheet).toMatch(
      /\.dashboard-input textarea::placeholder\s*\{[^}]*white-space:\s*nowrap;/s,
    );
    expect(stylesheet).toMatch(
      /\.dashboard-input textarea:placeholder-shown\s*\{[^}]*overflow-x:\s*hidden;[^}]*overflow-y:\s*hidden;[^}]*text-overflow:\s*clip;[^}]*white-space:\s*nowrap;/s,
    );
  });

  it("centers one-line dashboard input content like the native CenterStart decoration box", () => {
    expect(stylesheet).toMatch(
      /\.dashboard-input textarea\s*\{[^}]*align-content:\s*center;/s,
    );
  });

  it("applies the shared glass backdrop to Create contextual navigation", () => {
    expect(stylesheet).toMatch(
      /\.glass-action::before,\s*\.glass-back::before,\s*\.create-contextual-back::before\s*\{[^}]*backdrop-filter:\s*blur\(12px\);/s,
    );
  });

  it("keeps native Event paper buttons at the fixed clipped 58 point height", () => {
    expect(eventStoryStyles).toMatch(
      /\.event-history__paper-button-tilt\s*\{[^}]*height:\s*58\.203px;/s,
    );
    expect(eventStoryStyles).toMatch(
      /\.event-history__paper-button\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*display:\s*grid;[^}]*place-items:\s*center;[^}]*padding:\s*0;[^}]*overflow:\s*hidden;/s,
    );
  });

  it("removes browser textarea focus chrome while retaining button keyboard focus", () => {
    expect(stylesheet).toMatch(
      /button:focus-visible\s*\{[^}]*outline:\s*3px solid/s,
    );
    expect(stylesheet).toMatch(
      /textarea:focus,\s*textarea:focus-visible\s*\{[^}]*outline:\s*none;/s,
    );
    expect(stylesheet).toMatch(
      /\.dashboard-input textarea\s*\{[^}]*appearance:\s*none;[^}]*-webkit-appearance:\s*none;[^}]*outline:\s*none;/s,
    );
  });

  it("cancels Android WebView visual viewport panning without relayout", () => {
    expect(stylesheet).toMatch(
      /\.app\s*\{[^}]*transform:\s*translate3d\(0,\s*var\(--visual-viewport-offset-top\),\s*0\);/s,
    );
  });

  it("shows scroll-region focus only for focus-visible navigation", () => {
    expect(eventStoryStyles).toMatch(
      /\.event-history__scroll:focus,\s*\.scheduled-story__scroll:focus\s*\{[^}]*outline:\s*none;/s,
    );
    expect(eventStoryStyles).toMatch(
      /\.event-history__scroll:focus-visible,\s*\.scheduled-story__scroll:focus-visible\s*\{[^}]*outline:\s*3px solid[^}]*outline-offset:\s*-3px;/s,
    );
  });
});
