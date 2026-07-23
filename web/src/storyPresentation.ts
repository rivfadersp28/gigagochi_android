import type {
  ScheduledStoryData,
  ScheduledStoryOutcomeData,
} from "./EventStoryTypes";

export const ONBOARDING_BAT_STORY_ID_PREFIX = "onboarding-bat-help-v1-";

export function isOnboardingBatStory(story: ScheduledStoryData): boolean {
  return story.storyId.startsWith(ONBOARDING_BAT_STORY_ID_PREFIX);
}

export function storyQuestionParagraphs(story: ScheduledStoryData): string[] {
  if (!isOnboardingBatStory(story)) return [story.text, story.question];
  return [
    "Ой, что это?",
    "Под крышей пищит детёныш летучей мыши.",
    "Его мама рядом и хочет его накормить.",
    "Чтобы понять, чем она кормит малыша, нужно узнать, к какой группе относится летучая мышь.",
    story.question,
  ];
}

export function storyResultParagraphs(
  story: ScheduledStoryData,
  result: ScheduledStoryOutcomeData,
): string[] {
  if (!isOnboardingBatStory(story)) return [result.text, result.consequence];
  return [
    "Летучая мышь относится к млекопитающим.",
    "Мама добралась до малыша, согрела его и накормила молоком.",
  ];
}
