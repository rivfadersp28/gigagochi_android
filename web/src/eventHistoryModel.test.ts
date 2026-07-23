import { describe, expect, it } from "vitest";
import {
  closestCenterEventKey,
  createEventHistoryData,
  eventTimestampMillis,
  initialEventScrollTop,
  isBundledMediaRef,
  storyEventKey,
  travelEventCaption,
  travelEventKey,
} from "./eventHistoryModel";
import { localStoryFixture, travelVideoFixture } from "./test/eventStoryFixtures";

describe("authoritative event history model", () => {
  it("forms one newest-first chronology from the native-filtered event DTO", () => {
    const history = createEventHistoryData(
      [
        localStoryFixture({
          storyId: "answered-old",
          createdAt: "2026-07-18T18:00:00Z",
          selectedChoice: "Подойти",
          result: {
            text: "Итог",
            reaction: "Спасибо",
            consequence: "Всё получилось",
            experienceGained: 125,
          },
        }),
        localStoryFixture({ storyId: "pending-new", createdAt: "2026-07-19T19:00:00+03:00" }),
        localStoryFixture({
          storyId: "answered-new",
          createdAt: "2026-07-19T15:00:00Z",
          selectedChoice: "Позвать",
          result: {
            text: "Итог",
            reaction: "Спасибо",
            consequence: "Всё получилось",
            experienceGained: 100,
          },
        }),
        localStoryFixture({ storyId: "pending-old", createdAt: "2026-07-19T10:00:00Z" }),
      ],
      [
        travelVideoFixture("ready", { completedAtEpochMillis: Number.MAX_SAFE_INTEGER - 1 }),
        travelVideoFixture("newest", { completedAtEpochMillis: Number.MAX_SAFE_INTEGER }),
      ],
    );

    expect(history.items.map((item) => item.key)).toEqual([
      travelEventKey("newest"),
      travelEventKey("ready"),
      storyEventKey("pending-new"),
      storyEventKey("answered-new"),
      storyEventKey("pending-old"),
      storyEventKey("answered-old"),
    ]);
    expect(history.unansweredCount).toBe(2);
    expect(history.badgeCount(Number.MAX_SAFE_INTEGER - 2)).toBe(4);
    expect(history.badgeCount(Number.MAX_SAFE_INTEGER)).toBe(2);
  });

  it("uses descending keys for timestamp ties and pushes malformed dates to the end", () => {
    const history = createEventHistoryData([
      localStoryFixture({ storyId: "story-a", createdAt: "2026-07-19T10:00:00.999Z" }),
      localStoryFixture({ storyId: "story-z", createdAt: "2026-07-19T10:00:00.001Z" }),
      localStoryFixture({ storyId: "malformed", createdAt: "not-a-date" }),
    ]);

    expect(history.items.map((item) => item.key)).toEqual([
      storyEventKey("story-z"),
      storyEventKey("story-a"),
      storyEventKey("malformed"),
    ]);
    expect(eventTimestampMillis("2026-02-30T10:00:00Z")).toBe(Number.NEGATIVE_INFINITY);
    expect(eventTimestampMillis("0099-01-02T03:04:05Z")).toBe(
      new Date("0099-01-02T03:04:05Z").getTime(),
    );
    expect(history.latestTimestampMillis).toBe(eventTimestampMillis("2026-07-19T10:00:00Z"));
  });

  it("does not expose a pre-epoch or malformed watermark as latest", () => {
    expect(createEventHistoryData([
      localStoryFixture({ storyId: "old", createdAt: "1960-01-01T00:00:00Z" }),
      localStoryFixture({ storyId: "bad", createdAt: "broken" }),
    ]).latestTimestampMillis).toBeNull();
  });

  it("prefers a trimmed travel title and falls back to its prompt", () => {
    expect(travelEventCaption(travelVideoFixture("one", {
      title: "  Путешествие к маяку  ",
    }))).toBe("Путешествие к маяку");
    expect(travelEventCaption(travelVideoFixture("two", {
      title: " ",
      prompt: "Хочу увидеть море",
    }))).toBe("Хочу увидеть море");
  });
});

describe("closest-center autoplay contract", () => {
  it("selects the visible item whose center is closest to the viewport center", () => {
    expect(closestCenterEventKey(0, 874, [
      { key: "above", start: -600, end: -10 },
      { key: "middle", start: 180, end: 620 },
      { key: "bottom", start: 690, end: 1_200 },
    ])).toBe("middle");
  });

  it("keeps the first item on an exact tie and ignores fully hidden items", () => {
    expect(closestCenterEventKey(0, 100, [
      { key: "hidden", start: 101, end: 200 },
      { key: "first", start: 0, end: 40 },
      { key: "second", start: 60, end: 100 },
    ])).toBe("first");
    expect(closestCenterEventKey(0, 100, [
      { key: "hidden", start: 100, end: 200 },
    ])).toBeNull();
  });

  it("positions initial travel focus below content padding and clamps the scroll range", () => {
    expect(initialEventScrollTop(700, 82, 1_200)).toBe(618);
    expect(initialEventScrollTop(60, 82, 1_200)).toBe(0);
    expect(initialEventScrollTop(2_000, 82, 900)).toBe(900);
  });

  it("accepts only packaged or document-scoped appassets resource paths", () => {
    expect(isBundledMediaRef("/assets/media/story.mp4")).toBe(true);
    expect(isBundledMediaRef("/res/story.png")).toBe(true);
    expect(isBundledMediaRef(
      `/media/v1/${"a".repeat(32)}/${"9".repeat(32)}`,
    )).toBe(true);
    expect(isBundledMediaRef("https://example.test/story.mp4")).toBe(false);
    expect(isBundledMediaRef("data:image/png;base64,abc")).toBe(false);
    expect(isBundledMediaRef("/assets/../secret.mp4")).toBe(false);
    expect(isBundledMediaRef("/res/story.png?token=leak")).toBe(false);
    expect(isBundledMediaRef(`/media/v1/${"A".repeat(32)}/${"9".repeat(32)}`)).toBe(false);
    expect(isBundledMediaRef(`/media/v1/${"a".repeat(31)}/${"9".repeat(32)}`)).toBe(false);
    expect(isBundledMediaRef(
      `/media/v1/${"a".repeat(32)}/${"9".repeat(32)}#fragment`,
    )).toBe(false);
  });
});
