import { describe, expect, it } from "vitest";
import eventStory from "./eventStory.css?raw";
import styles from "./styles.css?raw";

const allCss = `${styles}\n${eventStory}`;

const expectedTokens = [
  "--platform-sp-neg-0-45",
  "--platform-sp-neg-0-15",
  "--platform-sp-11",
  "--platform-sp-12",
  "--platform-sp-13",
  "--platform-sp-14",
  "--platform-sp-15",
  "--platform-sp-16",
  "--platform-sp-16-25",
  "--platform-sp-17",
  "--platform-sp-18",
  "--platform-sp-18-2",
  "--platform-sp-20",
  "--platform-sp-22",
  "--platform-sp-22-6",
  "--platform-sp-23",
  "--platform-sp-23-445",
  "--platform-sp-23-9",
  "--platform-sp-24",
  "--platform-sp-26",
  "--platform-sp-28",
  "--platform-sp-30",
  "--platform-sp-31-5",
  "--platform-sp-32",
  "--platform-sp-38",
  "--platform-sp-39-45",
  "--platform-sp-40",
] as const;

describe("platform SP typography contract", () => {
  it("declares the complete closed token set and scales inherited startup copy", () => {
    const declarations = [...styles.matchAll(/^\s*(--platform-sp-[a-z0-9-]+)\s*:/gm)]
      .map((match) => match[1]);

    expect(new Set(declarations)).toEqual(new Set(expectedTokens));
    expect(declarations).toHaveLength(expectedTokens.length);
    expect(styles).toMatch(/:root\s*\{[^}]*font-size:\s*var\(--platform-sp-16\)/s);
  });

  it("routes every SP-valued text declaration through a platform token", () => {
    const declarations = [...allCss.matchAll(
      /\b(font-size|line-height|letter-spacing)\s*:\s*([^;]+);/g,
    )];

    declarations.forEach(([, property, rawValue]) => {
      const value = rawValue.trim();
      const allowedUnitlessLineHeight = property === "line-height" && /^(?:1|1\.35)$/.test(value);
      expect(
        value.startsWith("var(--platform-sp-") || allowedUnitlessLineHeight,
        `${property}: ${value}`,
      ).toBe(true);
    });
  });

  it("uses every injected token and leaves no Android-specific typography variable", () => {
    expectedTokens.forEach((token) => {
      const occurrences = allCss.split(token).length - 1;
      expect(occurrences, token).toBeGreaterThanOrEqual(2);
    });
    expect(allCss).not.toContain("--android-sp-");
  });
});
