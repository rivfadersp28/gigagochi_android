export interface ScheduledStoryResultData {
  text: string;
  reaction: string;
  consequence: string;
  experienceGained: number;
}

export interface ScheduledStoryData {
  storyId: string;
  title: string;
  text: string;
  question: string;
  choices: string[];
  createdAt: string;
  imageRef: string | null;
  videoRef: string | null;
  selectedChoice: string | null;
  result: ScheduledStoryResultData | null;
  resultImageRef: string | null;
  resultVideoRef: string | null;
}

export interface LocalScheduledStoryData {
  story: ScheduledStoryData;
}

export interface TravelVideoAssetData {
  requestKey: string;
  prompt: string;
  title: string | null;
  scenario: string | null;
  imageRef: string | null;
  videoRef: string | null;
  completedAtEpochMillis: number;
}

export interface WebEventsSnapshot {
  stories: LocalScheduledStoryData[];
  travelVideos: TravelVideoAssetData[];
  badgeCount: number;
  latestEventAtEpochMillis: number | null;
  lastViewedAtEpochMillis: number | null;
  initialFocusTravelRequestKey: string | null;
}

export type EventHistoryItemData =
  | {
      kind: "travel";
      key: string;
      timestampMillis: number;
      asset: TravelVideoAssetData;
    }
  | {
      kind: "story";
      key: string;
      timestampMillis: number;
      answered: boolean;
      item: LocalScheduledStoryData;
    };

export interface EventHistoryData {
  items: EventHistoryItemData[];
  unansweredCount: number;
  latestTimestampMillis: number | null;
  isEmpty: boolean;
  badgeCount(lastViewedAtEpochMillis: number | null): number;
}

export type TravelVideoShareResult = "opened";

export type ScheduledStoryPhase = "question" | "choicePending" | "retryable" | "result";

export type ScheduledStoryKind = "scheduled" | "onboardingBat";
export type ScheduledStoryOrigin = "dashboard" | "events";

export interface OpenedScheduledStoryData {
  storyId: string;
  title: string;
  text: string;
  question: string;
  choices: string[];
  enabledChoice: string | null;
  questionParagraphs: string[];
  imageRef: string | null;
  videoRef: string | null;
}

export interface ScheduledStoryOutcomeData extends ScheduledStoryResultData {
  requestKey: string;
  answer: string;
  paragraphs: string[];
  imageRef: string | null;
  videoRef: string | null;
}

export interface ScheduledStoryScreenState {
  phase: ScheduledStoryPhase;
  kind: ScheduledStoryKind;
  origin: ScheduledStoryOrigin;
  story: OpenedScheduledStoryData;
  durableRequestKey: string | null;
  pendingChoice: string | null;
  result: ScheduledStoryOutcomeData | null;
  error: string | null;
}

export type WebOpenedStorySnapshot = ScheduledStoryScreenState;

export interface EventLayoutCandidate {
  key: string;
  start: number;
  end: number;
}
