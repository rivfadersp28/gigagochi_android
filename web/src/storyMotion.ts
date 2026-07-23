import { motionConfig } from "./motionConfig";

export const STORY_GLYPH_STAGGER_MILLIS = motionConfig.story.glyphStaggerMillis;
export const STORY_GLYPH_RISE_MILLIS = motionConfig.story.glyphRiseDurationMillis;

export function storyRevealDuration(characterCount: number): number {
  return characterCount <= 0
    ? 0
    : STORY_GLYPH_RISE_MILLIS + (characterCount - 1) * STORY_GLYPH_STAGGER_MILLIS;
}
