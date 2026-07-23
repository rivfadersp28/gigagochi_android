import type {
  EventHistoryData,
  EventHistoryItemData,
  EventLayoutCandidate,
  LocalScheduledStoryData,
  TravelVideoAssetData,
} from "./EventStoryTypes";

const EVENT_TIMESTAMP_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/;
const OPAQUE_MEDIA_REFERENCE_PATTERN = /^\/media\/v1\/[a-f0-9]{32}\/[a-f0-9]{32}$/;
const PACKAGED_MEDIA_REFERENCE_PATTERN = /^\/(assets|res)\/[A-Za-z0-9][A-Za-z0-9._/-]*$/;

export function storyEventKey(storyId: string): string {
  return `story:${storyId}`;
}

export function travelEventKey(requestKey: string): string {
  return `travel:${requestKey}`;
}

export function travelEventCaption(asset: TravelVideoAssetData): string {
  return asset.title?.trim() || asset.prompt.trim();
}

export function eventTimestampMillis(value: string): number {
  const match = EVENT_TIMESTAMP_PATTERN.exec(value);
  if (!match) return Number.NEGATIVE_INFINITY;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (
    month < 1 || month > 12 || day < 1 || day > 31 ||
    hour > 23 || minute > 59 || second > 59
  ) return Number.NEGATIVE_INFINITY;

  const zone = match[7];
  let offsetMinutes = 0;
  if (zone !== "Z") {
    const sign = zone[0] === "+" ? 1 : -1;
    const zoneHour = Number(zone.slice(1, 3));
    const zoneMinute = Number(zone.slice(4, 6));
    if (zoneHour > 23 || zoneMinute > 59) return Number.NEGATIVE_INFINITY;
    offsetMinutes = sign * (zoneHour * 60 + zoneMinute);
  }
  if (year === 0) return Number.NEGATIVE_INFINITY;
  const verification = new Date(0);
  verification.setUTCFullYear(year, month - 1, day);
  verification.setUTCHours(hour, minute, second, 0);
  if (
    verification.getUTCFullYear() !== year ||
    verification.getUTCMonth() !== month - 1 ||
    verification.getUTCDate() !== day ||
    verification.getUTCHours() !== hour ||
    verification.getUTCMinutes() !== minute ||
    verification.getUTCSeconds() !== second
  ) return Number.NEGATIVE_INFINITY;
  const localUtc = verification.getTime();
  return localUtc - offsetMinutes * 60_000;
}

function descendingEventOrder(left: EventHistoryItemData, right: EventHistoryItemData): number {
  if (left.timestampMillis !== right.timestampMillis) {
    return left.timestampMillis > right.timestampMillis ? -1 : 1;
  }
  if (left.key === right.key) return 0;
  return left.key > right.key ? -1 : 1;
}

export function createEventHistoryData(
  stories: LocalScheduledStoryData[],
  travelVideos: TravelVideoAssetData[] = [],
): EventHistoryData {
  const items: EventHistoryItemData[] = [
    ...stories.map((item): EventHistoryItemData => ({
      kind: "story",
      key: storyEventKey(item.story.storyId),
      timestampMillis: eventTimestampMillis(item.story.createdAt),
      answered: item.story.selectedChoice !== null,
      item,
    })),
    ...travelVideos
      .map((asset): EventHistoryItemData => ({
        kind: "travel",
        key: travelEventKey(asset.requestKey),
        timestampMillis: asset.completedAtEpochMillis,
        asset,
      })),
  ].sort(descendingEventOrder);
  const unansweredCount = items.filter((item) => item.kind === "story" && !item.answered).length;
  const newestTimestamp = items.length > 0
    ? Math.max(...items.map((item) => item.timestampMillis))
    : Number.NEGATIVE_INFINITY;
  const latestTimestampMillis = Number.isFinite(newestTimestamp) && newestTimestamp >= 0
    ? newestTimestamp
    : null;
  return {
    items,
    unansweredCount,
    latestTimestampMillis,
    isEmpty: items.length === 0,
    badgeCount(lastViewedAtEpochMillis) {
      return items.filter((item) => {
        const needsAnswer = item.kind === "story" && !item.answered;
        const isNew = lastViewedAtEpochMillis === null ||
          item.timestampMillis > lastViewedAtEpochMillis;
        return needsAnswer || isNew;
      }).length;
    },
  };
}

export function closestCenterEventKey(
  viewportStart: number,
  viewportEnd: number,
  candidates: EventLayoutCandidate[],
): string | null {
  const viewportCenter = (viewportStart + viewportEnd) / 2;
  let closest: EventLayoutCandidate | null = null;
  let closestDistance = Number.POSITIVE_INFINITY;
  for (const candidate of candidates) {
    if (candidate.end <= viewportStart || candidate.start >= viewportEnd) continue;
    const distance = Math.abs((candidate.start + candidate.end) / 2 - viewportCenter);
    if (distance < closestDistance) {
      closest = candidate;
      closestDistance = distance;
    }
  }
  return closest?.key ?? null;
}

export function initialEventScrollTop(
  itemOffsetTop: number,
  contentPaddingTop: number,
  maximumScrollTop: number,
): number {
  const requested = itemOffsetTop - contentPaddingTop;
  return Math.min(Math.max(0, requested), Math.max(0, maximumScrollTop));
}

export function isBundledMediaRef(value: string | null | undefined): value is string {
  if (!value) return false;
  if (OPAQUE_MEDIA_REFERENCE_PATTERN.test(value)) return true;
  if (!PACKAGED_MEDIA_REFERENCE_PATTERN.test(value)) return false;
  return value.split("/").slice(2).every((segment) => (
    segment.length > 0 && segment !== "." && segment !== ".."
  ));
}
