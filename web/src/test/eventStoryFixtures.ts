import type {
  LocalScheduledStoryData,
  ScheduledStoryData,
  ScheduledStoryOutcomeData,
  ScheduledStoryScreenState,
  TravelVideoAssetData,
} from "../EventStoryTypes";

export function scheduledStoryFixture(
  overrides: Partial<ScheduledStoryData> = {},
): ScheduledStoryData {
  return {
    storyId: "android-story-fixture-00000000000000000000",
    title: "Шорох у старого дерева",
    text: "Тото услышал шорох и заметил, что кому-то нужна помощь.",
    question: "Что ему сделать?",
    choices: ["Подойти", "Позвать", "Спрятаться", "Подождать"],
    createdAt: "2026-07-19T10:00:00Z",
    imageRef: "/res/onboarding_bat_situation.png",
    videoRef: "/assets/media/onboarding-bat-situation.mp4",
    selectedChoice: null,
    result: null,
    resultImageRef: null,
    resultVideoRef: null,
    ...overrides,
  };
}

export function localStoryFixture(
  overrides: Partial<ScheduledStoryData> = {},
): LocalScheduledStoryData {
  return {
    story: scheduledStoryFixture(overrides),
  };
}

export function storyOutcomeFixture(
  overrides: Partial<ScheduledStoryOutcomeData> = {},
): ScheduledStoryOutcomeData {
  return {
    requestKey: "choice-fixture",
    answer: "Подойти",
    text: "Герой помог малышу выбраться.",
    reaction: "Спасибо, теперь всё хорошо.",
    consequence: "Малыш снова рядом с семьёй.",
    experienceGained: 125,
    paragraphs: [
      "Герой помог малышу выбраться.",
      "Малыш снова рядом с семьёй.",
    ],
    imageRef: "/res/onboarding_bat_success.png",
    videoRef: "/assets/media/onboarding-bat-success.mp4",
    ...overrides,
  };
}

export function storyScreenFixture(
  overrides: Partial<ScheduledStoryScreenState> = {},
): ScheduledStoryScreenState {
  const eventStory = scheduledStoryFixture();
  return {
    phase: "question",
    kind: "scheduled",
    origin: "events",
    story: {
      storyId: eventStory.storyId,
      title: eventStory.title,
      text: eventStory.text,
      question: eventStory.question,
      choices: eventStory.choices,
      enabledChoice: null,
      questionParagraphs: [eventStory.text, eventStory.question],
      imageRef: eventStory.imageRef,
      videoRef: eventStory.videoRef,
    },
    durableRequestKey: null,
    pendingChoice: null,
    result: null,
    error: null,
    ...overrides,
  };
}

export function travelVideoFixture(
  requestKey = "travel-fixture",
  overrides: Partial<TravelVideoAssetData> = {},
): TravelVideoAssetData {
  return {
    requestKey,
    prompt: "Хочу увидеть море",
    title: "Путешествие к маяку",
    scenario: "Тото увидел маяк у моря.",
    imageRef: "/res/onboarding_bat_situation.png",
    videoRef: "/assets/media/onboarding-bat-situation.mp4",
    completedAtEpochMillis: 100,
    ...overrides,
  };
}
