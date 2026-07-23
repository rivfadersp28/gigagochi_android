import { describe, expect, it } from "vitest";
import { petTapHeartParticles } from "./PetTapHeartCanvas";
import { petTapBulgeStrength } from "./usePetTapBulge";

describe("pet tap presentation contract", () => {
  it("uses nine deterministic heart particles", () => {
    expect(petTapHeartParticles(7)).toHaveLength(9);
    expect(petTapHeartParticles(7)).toEqual(petTapHeartParticles(7));
    expect(petTapHeartParticles(7)).not.toEqual(petTapHeartParticles(8));
  });

  it("matches normal and reduced bulge envelopes", () => {
    expect(petTapBulgeStrength(-1, false)).toBe(0);
    expect(petTapBulgeStrength(120, false)).toBeCloseTo(.18);
    expect(petTapBulgeStrength(250, false)).toBe(0);
    expect(petTapBulgeStrength(35, true)).toBeCloseTo(.08);
    expect(petTapBulgeStrength(100, true)).toBe(0);
  });
});
