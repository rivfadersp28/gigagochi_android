import {
  BRIDGE_PROTOCOL_VERSION,
  type AppRoute,
  type AppSnapshot,
  type BridgeMethod,
  type JsonValue,
  type ProductCommand,
} from "./contracts";
import { BridgeError, type GigagochiBridge } from "./bridge";
import { uuidV4 } from "./uuid";
import type { WebEventsSnapshot, WebOpenedStorySnapshot } from "./EventStoryTypes";

const creationQuestions = [
  ["Кого хочешь создать?", "Ледяного дракона", "Человек-яблоко", "Водяной дух"],
  ["Как его будут звать?", "Тото", "Бачок", "Денис"],
  ["Какой у него характер?", "Добрый", "Злой", "Ленивый"],
  ["Чего он боится?", "Пауков", "Бизнесменов", "Людоедов"],
  ["Какой у него любимый предмет?", "Вантуз", "Рулон бумаги", "Кока кола"],
] as const;

function createMockEventsSnapshot(): WebEventsSnapshot {
  return {
    stories: [],
    travelVideos: [],
    badgeCount: 0,
    latestEventAtEpochMillis: null,
    lastViewedAtEpochMillis: null,
    initialFocusTravelRequestKey: null,
  };
}

function createMockStorySnapshot(
  kind: "scheduled" | "onboardingBat" = "scheduled",
  origin: "events" | "dashboard" = "events",
  storyId = kind === "onboardingBat"
    ? "onboarding-bat-help-v1"
    : "mock-story",
): WebOpenedStorySnapshot {
  const onboarding = kind === "onboardingBat";
  const question = onboarding
    ? "К какой группе относится летучая мышь?"
    : "Что ему сделать?";
  const choices = onboarding
    ? ["Птицы", "Млекопитающие", "Насекомые", "Пресмыкающиеся"]
    : ["Подойти", "Позвать", "Спрятаться", "Подождать"];
  const text = "Тото услышал шорох и заметил, что кому-то нужна помощь.";
  return {
    phase: "question",
    kind,
    origin,
    story: {
      storyId,
      title: onboarding ? "Малыш летучей мыши" : "Шорох у старого дерева",
      text,
      question,
      choices,
      enabledChoice: onboarding ? "Млекопитающие" : null,
      questionParagraphs: onboarding
        ? [
            "Ой, что это?",
            "Под крышей пищит детёныш летучей мыши.",
            "Его мама рядом и хочет его накормить.",
            "Чтобы понять, чем она кормит малыша, нужно узнать, к какой группе относится летучая мышь.",
            question,
          ]
        : [text, question],
      imageRef: onboarding ? "/res/onboarding_bat_situation.png" : null,
      videoRef: onboarding ? "/assets/media/onboarding-bat-situation.mp4" : null,
    },
    durableRequestKey: null,
    pendingChoice: null,
    result: null,
    error: null,
  };
}

export function createMockSnapshot(route: AppRoute): AppSnapshot {
  const question = creationQuestions[0];
  return {
    protocolVersion: BRIDGE_PROTOCOL_VERSION,
    appVersion: "0.1.13-webview-dev",
    webBundleVersion: "0.1.0",
    revision: "mock-0",
    route,
    dashboardMode: "idle",
    capabilities: {
      requestNotificationPermission: true,
      shareTravelVideo: true,
      feedback: true,
      navigationReady: true,
      opaqueMedia: true,
    },
    pendingDeepLinkTarget: null,
    reducedMotion: false,
    notificationPermission: "unknown",
    safeArea: {
      top: 0,
      right: 0,
      bottom: 0,
      left: 0,
      imeTop: 874,
      imeHeight: 0,
      imeProgress: 0,
    },
    create:
      route === "create"
        ? {
            step: 0,
            title: question[0],
            options: question.slice(1),
            nextQuestion: {
              title: creationQuestions[1][0],
              options: [...creationQuestions[1].slice(1)],
            },
            phase: "initial",
            generation: "idle",
            error: null,
            retryTarget: null,
          }
        : null,
    firstSession: null,
    dashboard: route === "create"
      ? null
      : {
          reply: null,
          chat: {
            draft: "",
            error: null,
            activeRequestKey: null,
            queuedRequestKey: null,
            thinking: false,
          },
          feed: {
            error: null,
            activeRequestKey: null,
            activeFood: null,
            audioIndex: null,
            pulseId: 0,
            thinking: false,
          },
          outfit: {
            draft: "",
            error: null,
            activeRequestKey: null,
            thinking: false,
            experienceCost: 200,
            pending: null,
          },
          travel: {
            draft: "",
            error: null,
            activeRequestKey: null,
            thinking: false,
            pending: null,
          },
        },
    events: route === "create" ? null : createMockEventsSnapshot(),
    story: route === "story" ? createMockStorySnapshot() : null,
    pending: {
      chat: null,
      outfit: null,
      travel: null,
    },
    petTapFeedback: null,
    pet:
      route === "create"
        ? null
        : {
            name: "Тото",
            stageLabel: "Малыш",
            experience: 240,
            hunger: 74,
            happiness: 82,
            energy: 68,
            message: "Рад тебя видеть! Чем займёмся сегодня?",
            petTapProgress: 0,
            media: {
              videoRef: "/assets/media/openai-normal.mp4",
              posterRef: null,
              sadVideoRef: null,
              happyVideoRef: null,
            },
          },
  };
}

function routeFromQuery(): AppRoute {
  const fixture = new URLSearchParams(window.location.search).get("fixture");
  if (fixture === "create") return "create";
  if (fixture === "connection-error") return "connectionError";
  if (fixture === "local-data-error") return "localDataError";
  if (fixture === "events") return "events";
  if (fixture === "story") return "story";
  return "dashboard";
}

export class MockGigagochiBridge implements GigagochiBridge {
  private snapshot: AppSnapshot;
  private revision: number;

  constructor(initialSnapshot: AppSnapshot = createMockSnapshot(routeFromQuery())) {
    this.snapshot = cloneJson(initialSnapshot);
    this.revision = mockRevisionNumber(initialSnapshot.revision);
  }

  async bootstrap(): Promise<AppSnapshot> {
    return cloneJson(this.snapshot);
  }

  async dispatch(command: ProductCommand): Promise<AppSnapshot> {
    if (command.expectedSnapshotRevision !== this.snapshot.revision) {
      throw new BridgeError("STATE_CONFLICT", true);
    }
    switch (command.type) {
      case "CREATE_ANSWER":
        if (command.payload.step !== this.snapshot.create?.step) {
          throw new Error("WRONG_STAGE");
        }
        this.advanceCreate();
        break;
      case "CREATE_BACKGROUND_COMPLETE":
        this.completeCreateBackground();
        break;
      case "CREATE_RETRY":
        this.retryCreate();
        break;
      case "CREATE_FINISH":
        this.snapshot = { ...createMockSnapshot("dashboard"), revision: this.nextRevision() };
        break;
      case "BACK":
        if (this.snapshot.route === "story") {
          this.snapshot = {
            ...this.snapshot,
            route: this.snapshot.story?.origin === "events" ? "events" : "dashboard",
            story: null,
            revision: this.nextRevision(),
          };
        } else if (this.snapshot.route === "events") {
          this.snapshot = {
            ...this.snapshot,
            route: "dashboard",
            revision: this.nextRevision(),
          };
        }
        break;
      case "DASHBOARD_OPEN_MODE":
        this.snapshot = {
          ...this.snapshot,
          dashboardMode: command.payload.mode,
          revision: this.nextRevision(),
        };
        break;
      case "DASHBOARD_CLOSE_MODE":
        this.snapshot = {
          ...this.snapshot,
          dashboardMode: "idle",
          revision: this.nextRevision(),
        };
        break;
      case "DASHBOARD_UPDATE_DRAFT": {
        const dashboard = this.snapshot.dashboard;
        if (
          this.snapshot.route !== "dashboard" ||
          !dashboard ||
          this.snapshot.dashboardMode !== command.payload.mode
        ) {
          throw new BridgeError("WRONG_STAGE", false);
        }
        const mode = command.payload.mode;
        this.snapshot = {
          ...this.snapshot,
          revision: this.nextRevision(),
          dashboard: {
            ...dashboard,
            [mode]: {
              ...dashboard[mode],
              draft: command.payload.value,
              error: null,
            },
          },
        };
        break;
      }
      case "CHAT_SEND":
        this.applyChat(command.payload.message);
        break;
      case "CHAT_RETRY": {
        const dashboard = this.snapshot.dashboard;
        if (
          this.snapshot.route !== "dashboard" ||
          this.snapshot.dashboardMode !== "chat" ||
          !dashboard ||
          dashboard.chat.activeRequestKey === null ||
          dashboard.chat.error === null ||
          dashboard.chat.thinking
        ) {
          throw new BridgeError("WRONG_STAGE", false);
        }
        this.snapshot = {
          ...this.snapshot,
          revision: this.nextRevision(),
          dashboard: {
            ...dashboard,
            chat: {
              ...dashboard.chat,
              draft: "",
              error: null,
              thinking: true,
            },
          },
        };
        break;
      }
      case "FEED_CONSUME":
        this.applyFeed(command.payload.food);
        break;
      case "OUTFIT_SUBMIT":
        this.submitOutfit(command.requestKey, command.payload.prompt);
        break;
      case "OUTFIT_RETRY":
        this.retryOutfit();
        break;
      case "TRAVEL_SUBMIT":
        this.submitTravel(command.requestKey, command.payload.prompt);
        break;
      case "TRAVEL_RETRY":
        this.retryTravel();
        break;
      case "REPLY_ADVANCE":
        this.advanceReply(command.payload.requestKey);
        break;
      case "REPLY_COMPLETE":
      case "CHAT_REPLY_PRESENTED":
        this.snapshot = { ...this.snapshot, revision: this.nextRevision() };
        break;
      case "PET_TAP":
        this.applyPetTap();
        break;
      case "EVENTS_MARK_VIEWED":
        this.snapshot = {
          ...this.snapshot,
          events: this.snapshot.events ? {
            ...this.snapshot.events,
            badgeCount: 0,
            lastViewedAtEpochMillis: command.payload.viewedAt,
          } : null,
          revision: this.nextRevision(),
        };
        break;
      case "STORY_OPEN":
        this.snapshot = {
          ...this.snapshot,
          route: "story",
          story: createMockStorySnapshot("scheduled", "events", command.payload.storyId),
          revision: this.nextRevision(),
        };
        break;
      case "STORY_CHOOSE":
        this.applyStoryChoice(command.payload.choice, command.requestKey);
        break;
      case "STORY_RETRY":
        if (this.snapshot.story?.pendingChoice && this.snapshot.story.durableRequestKey) {
          this.applyStoryChoice(
            this.snapshot.story.pendingChoice,
            this.snapshot.story.durableRequestKey,
          );
        }
        break;
      case "STORY_FINISH":
        this.snapshot = {
          ...this.snapshot,
          route: this.snapshot.story?.origin === "events" ? "events" : "dashboard",
          story: null,
          revision: this.nextRevision(),
        };
        break;
      case "NAVIGATE":
        this.snapshot = command.payload.route === "travel"
          ? {
              ...this.snapshot,
              route: "story",
              story: createMockStorySnapshot("onboardingBat", "dashboard"),
              revision: this.nextRevision(),
            }
          : {
              ...this.snapshot,
              route: command.payload.route,
              revision: this.nextRevision(),
            };
        break;
      default:
        throw new BridgeError("UNSUPPORTED_METHOD", false);
    }
    const result = cloneJson(this.snapshot);
    this.snapshot = { ...this.snapshot, petTapFeedback: null };
    return result;
  }

  async call(method: BridgeMethod, payload?: JsonValue): Promise<JsonValue> {
    if (method !== "shareTravelVideo") return {};
    const requestKey = typeof payload === "object" && payload !== null && !Array.isArray(payload)
      ? payload.requestKey
      : null;
    if (typeof requestKey !== "string" || requestKey.length === 0) {
      throw new BridgeError("INVALID_PAYLOAD", false);
    }
    queueMicrotask(() => {
      window.dispatchEvent(new CustomEvent("gigagochi:native-event", {
        detail: {
          type: "travelShareCompleted",
          payload: { requestKey, status: "opened" },
        },
      }));
    });
    return "accepted";
  }

  private applyStoryChoice(choice: string, requestKey: string): void {
    const story = this.snapshot.story;
    if (!story) return;
    this.snapshot = {
      ...this.snapshot,
      story: {
        ...story,
        phase: "result",
        durableRequestKey: requestKey,
        pendingChoice: null,
        error: null,
        result: {
          requestKey,
          answer: choice,
          text: "Герой помог малышу выбраться.",
          reaction: "Спасибо, теперь всё хорошо.",
          consequence: "Малыш снова рядом с семьёй.",
          experienceGained: 125,
          paragraphs: story.kind === "onboardingBat"
            ? [
                "Летучая мышь относится к млекопитающим.",
                "Мама добралась до малыша, согрела его и накормила молоком.",
              ]
            : ["Герой помог малышу выбраться.", "Малыш снова рядом с семьёй."],
          imageRef: story.kind === "onboardingBat"
            ? "/res/onboarding_bat_success.png"
            : null,
          videoRef: story.kind === "onboardingBat"
            ? "/assets/media/onboarding-bat-success.mp4"
            : null,
        },
      },
      revision: this.nextRevision(),
    };
  }

  private advanceCreate(): void {
    const create = this.snapshot.create;
    if (!create) return;
    const nextStep = Math.min(create.step + 1, creationQuestions.length);
    const question = creationQuestions[nextStep];
    const nextQuestion = creationQuestions[nextStep + 1];
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      create: {
        ...create,
        step: nextStep,
        title: question?.[0] ?? "Твой новый друг уже рядом",
        options: question ? [...question.slice(1)] : [],
        nextQuestion: nextQuestion
          ? { title: nextQuestion[0], options: [...nextQuestion.slice(1)] }
          : null,
        phase: nextStep === 1 ? "transition" : "formed",
        generation: nextStep === creationQuestions.length ? "ready" : "running",
      },
    };
  }

  private completeCreateBackground(): void {
    const create = this.snapshot.create;
    if (!create || create.phase !== "transition") return;
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      create: { ...create, phase: "formed" },
    };
  }

  private retryCreate(): void {
    const create = this.snapshot.create;
    if (
      this.snapshot.route !== "create" ||
      !create ||
      (create.generation !== "retryable" && create.generation !== "failed")
    ) {
      throw new BridgeError("WRONG_STAGE", false);
    }
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      create: {
        ...create,
        generation: "running",
        error: null,
        retryTarget: null,
      },
    };
  }

  private submitOutfit(requestKey: string, rawPrompt: string): void {
    const dashboard = this.snapshot.dashboard;
    const pet = this.snapshot.pet;
    const prompt = requiredMockPrompt(rawPrompt);
    const currentPending = dashboard?.outfit.pending;
    if (
      this.snapshot.route !== "dashboard" ||
      !dashboard ||
      !pet ||
      (this.snapshot.dashboardMode !== "idle" && this.snapshot.dashboardMode !== "outfit") ||
      (currentPending != null &&
        currentPending.status !== "failed" &&
        currentPending.status !== "applyConflict") ||
      pet.experience < dashboard.outfit.experienceCost
    ) {
      throw new BridgeError("WRONG_STAGE", false);
    }
    const pending = {
      requestKey,
      status: "attached" as const,
      prompt,
      displayItem: prompt,
      experienceCost: dashboard.outfit.experienceCost,
    };
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      dashboardMode: "idle",
      pet: { ...pet, experience: pet.experience - dashboard.outfit.experienceCost },
      dashboard: {
        ...dashboard,
        reply: mockTransientReply(requestKey, `Наряд «${prompt}» создаётся.`),
        outfit: {
          ...dashboard.outfit,
          draft: "",
          error: null,
          activeRequestKey: null,
          thinking: false,
          pending,
        },
      },
      pending: {
        ...this.snapshot.pending,
        outfit: { requestKey, status: "attached", prompt },
      },
    };
  }

  private retryOutfit(): void {
    const dashboard = this.snapshot.dashboard;
    const pending = dashboard?.outfit.pending;
    if (
      this.snapshot.route !== "dashboard" ||
      !dashboard ||
      !pending ||
      (pending.status !== "pending" && pending.status !== "retryable")
    ) {
      throw new BridgeError("WRONG_STAGE", false);
    }
    const attached = { ...pending, status: "attached" as const };
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      dashboardMode: "idle",
      dashboard: {
        ...dashboard,
        reply: mockTransientReply(
          pending.requestKey,
          `Повторно создаю наряд «${pending.displayItem}».`,
        ),
        outfit: {
          ...dashboard.outfit,
          error: null,
          activeRequestKey: null,
          thinking: false,
          pending: attached,
        },
      },
      pending: {
        ...this.snapshot.pending,
        outfit: {
          requestKey: pending.requestKey,
          status: "attached",
          prompt: pending.prompt,
        },
      },
    };
  }

  private submitTravel(requestKey: string, rawPrompt: string): void {
    const dashboard = this.snapshot.dashboard;
    const prompt = requiredMockPrompt(rawPrompt);
    const currentPending = dashboard?.travel.pending;
    if (
      this.snapshot.route !== "dashboard" ||
      !dashboard ||
      (this.snapshot.dashboardMode !== "idle" && this.snapshot.dashboardMode !== "travel") ||
      (currentPending != null &&
        currentPending.status !== "failed" &&
        currentPending.status !== "applyConflict")
    ) {
      throw new BridgeError("WRONG_STAGE", false);
    }
    const pending = { requestKey, status: "attached" as const, prompt };
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      dashboardMode: "idle",
      dashboard: {
        ...dashboard,
        reply: mockTransientReply(requestKey, `Путешествие «${prompt}» готовится.`),
        travel: {
          ...dashboard.travel,
          draft: "",
          error: null,
          activeRequestKey: null,
          thinking: false,
          pending,
        },
      },
      pending: {
        ...this.snapshot.pending,
        travel: pending,
      },
    };
  }

  private retryTravel(): void {
    const dashboard = this.snapshot.dashboard;
    const pending = dashboard?.travel.pending;
    if (
      this.snapshot.route !== "dashboard" ||
      !dashboard ||
      !pending ||
      (pending.status !== "pending" && pending.status !== "retryable")
    ) {
      throw new BridgeError("WRONG_STAGE", false);
    }
    const attached = { ...pending, status: "attached" as const };
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      dashboardMode: "idle",
      dashboard: {
        ...dashboard,
        reply: mockTransientReply(
          pending.requestKey,
          `Повторно готовлю путешествие «${pending.prompt}».`,
        ),
        travel: {
          ...dashboard.travel,
          error: null,
          activeRequestKey: null,
          thinking: false,
          pending: attached,
        },
      },
      pending: {
        ...this.snapshot.pending,
        travel: attached,
      },
    };
  }

  private applyFeed(food: string): void {
    const pet = this.snapshot.pet;
    const dashboard = this.snapshot.dashboard;
    if (!pet || !dashboard) return;
    const reply = food === "berry-bowl" ? "Ням-ням!" : "Мне легче, но это ужасная гадость!!";
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      pet: {
        ...pet,
        hunger: food === "berry-bowl" ? Math.min(100, pet.hunger + 25) : pet.hunger,
        energy: food === "leaf-crunch" ? Math.min(100, pet.energy + 25) : pet.energy,
        message: reply,
      },
      dashboard: {
        ...dashboard,
        reply: {
          source: "feed",
          requestKey: uuidV4(),
          portions: [reply],
          portionIndex: 0,
          hasNextPortion: false,
          autoAdvanceDelayMillis: 6_000,
        },
        feed: {
          ...dashboard.feed,
          activeRequestKey: null,
          activeFood: food === "berry-bowl" ? "berry-bowl" : "leaf-crunch",
          audioIndex: ((dashboard.feed.audioIndex ?? -1) + 1) % 3,
          pulseId: dashboard.feed.pulseId + 1,
          thinking: false,
        },
      },
    };
  }

  private applyChat(_message: string): void {
    const dashboard = this.snapshot.dashboard;
    if (!dashboard) return;
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      dashboard: {
        ...dashboard,
        reply: {
          source: "chat",
          requestKey: uuidV4(),
          portions: ["Слышал, почему лёд плавает?"],
          portionIndex: 0,
          hasNextPortion: false,
          autoAdvanceDelayMillis: 6_000,
        },
        chat: {
          ...dashboard.chat,
          activeRequestKey: null,
          queuedRequestKey: null,
          thinking: false,
        },
      },
    };
  }

  private advanceReply(requestKey: string): void {
    const dashboard = this.snapshot.dashboard;
    const reply = dashboard?.reply;
    if (!dashboard || !reply || reply.requestKey !== requestKey || !reply.hasNextPortion) return;
    const portionIndex = reply.portionIndex + 1;
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      dashboard: {
        ...dashboard,
        reply: {
          ...reply,
          portionIndex,
          hasNextPortion: portionIndex < reply.portions.length - 1,
        },
      },
    };
  }

  private applyPetTap(): void {
    const pet = this.snapshot.pet;
    if (!pet) return;
    const rewarded = pet.petTapProgress === 4;
    this.snapshot = {
      ...this.snapshot,
      revision: this.nextRevision(),
      pet: {
        ...pet,
        petTapProgress: rewarded ? 0 : pet.petTapProgress + 1,
        happiness: rewarded ? Math.min(100, pet.happiness + 15) : pet.happiness,
      },
      petTapFeedback: {
        eventId: uuidV4(),
        rewarded,
        thanks: rewarded ? "Приятно!" : null,
        visibleMillis: 5_000,
      },
    };
  }

  private nextRevision(): string {
    this.revision += 1;
    return `mock-${this.revision}`;
  }
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function mockRevisionNumber(revision: string): number {
  const match = /^mock-(\d+)$/.exec(revision);
  return match ? Number(match[1]) : 0;
}

function requiredMockPrompt(raw: string): string {
  const prompt = raw.trim();
  if (!prompt || prompt.length > 1_000) throw new BridgeError("INVALID_PAYLOAD", false);
  return prompt;
}

function mockTransientReply(requestKey: string, message: string) {
  return {
    source: "transient" as const,
    requestKey,
    portions: [message],
    portionIndex: 0,
    hasNextPortion: false,
    autoAdvanceDelayMillis: 6_000,
  };
}

export function createMockBridge(): MockGigagochiBridge {
  return new MockGigagochiBridge();
}
