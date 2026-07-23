import type { CSSProperties, ReactNode } from "react";
import { STORY_GLYPH_STAGGER_MILLIS } from "./storyMotion";

interface GlyphRevealTextProps {
  text: string;
  startIndex: number;
  baseDelayMillis: number;
  muted?: boolean;
  reducedMotion: boolean;
}

export function GlyphRevealText({
  text,
  startIndex,
  baseDelayMillis,
  muted = false,
  reducedMotion,
}: GlyphRevealTextProps) {
  let localIndex = 0;
  const content: ReactNode[] = text.split(/(\s+)/).map((token, tokenIndex) => {
    const tokenStart = localIndex;
    localIndex += token.length;
    if (/^\s+$/.test(token)) return token;
    return (
      <span className="scheduled-story__glyph-word" key={`${tokenIndex}-${tokenStart}`}>
        {Array.from(token).map((character, characterIndex) => {
          const delay = baseDelayMillis +
            (startIndex + tokenStart + characterIndex) * STORY_GLYPH_STAGGER_MILLIS;
          return (
            <span
              className="scheduled-story__glyph"
              data-glyph-index={startIndex + tokenStart + characterIndex}
              key={`${characterIndex}-${character}`}
              style={{
                "--story-glyph-delay": `${delay}ms`,
              } as CSSProperties}
            >
              {character}
            </span>
          );
        })}
      </span>
    );
  });

  return (
    <p
      className={[
        "scheduled-story__paragraph",
        muted ? "scheduled-story__paragraph--muted" : "",
        reducedMotion ? "scheduled-story__paragraph--reduced" : "",
      ].filter(Boolean).join(" ")}
    >
      <span className="sr-only">{text}</span>
      <span aria-hidden="true">{content}</span>
    </p>
  );
}
