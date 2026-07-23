import { describe, expect, it } from "vitest";
import { type AppSnapshot, isAppSnapshot, makeProductCommand } from "./contracts";
import { createMockSnapshot, MockGigagochiBridge } from "./mockBridge";

describe("MockGigagochiBridge", () => {
  it("publishes the next Create question and complete dashboard operation defaults", () => {
    const create = createMockSnapshot("create");
    const dashboard = createMockSnapshot("dashboard");

    expect(create.create?.nextQuestion).toEqual({
      title: "Как его будут звать?",
      options: ["Тото", "Бачок", "Денис"],
    });
    expect(dashboard.dashboard?.outfit).toMatchObject({ experienceCost: 200, pending: null });
    expect(dashboard.dashboard?.travel).toMatchObject({ pending: null });
    expect(isAppSnapshot(create)).toBe(true);
    expect(isAppSnapshot(dashboard)).toBe(true);
  });

  it("advances and acknowledges the Create background transition", async () => {
    window.history.replaceState({}, "", "/?fixture=create");
    const bridge = new MockGigagochiBridge();
    try {
      let snapshot = await bridge.bootstrap();
      snapshot = await bridge.dispatch(
        makeProductCommand(
          "CREATE_ANSWER",
          { answer: "Ледяного дракона", step: 0 },
          snapshot.revision,
        ),
      );
      expect(snapshot.create).toMatchObject({
        step: 1,
        title: "Как его будут звать?",
        phase: "transition",
      });
      expect(snapshot.create?.nextQuestion?.title).toBe("Какой у него характер?");

      snapshot = await bridge.dispatch(
        makeProductCommand("CREATE_BACKGROUND_COMPLETE", {}, snapshot.revision),
      );
      expect(snapshot.create?.phase).toBe("formed");
    } finally {
      window.history.replaceState({}, "", "/");
    }
  });

  it("retries a failed Create generation and rejects retry outside the retry stage", async () => {
    const initial: AppSnapshot = {
      ...createMockSnapshot("create"),
      revision: "mock-7",
      create: {
        ...createMockSnapshot("create").create!,
        generation: "retryable",
        error: "Не получилось создать питомца. Попробуйте ещё раз.",
        retryTarget: "generation",
      },
    };
    const bridge = new MockGigagochiBridge(initial);

    const retried = await bridge.dispatch(
      makeProductCommand("CREATE_RETRY", {}, initial.revision),
    );

    expect(retried.revision).toBe("mock-8");
    expect(retried.create).toMatchObject({
      generation: "running",
      error: null,
      retryTarget: null,
    });
    expect(isAppSnapshot(retried)).toBe(true);

    const invalid = new MockGigagochiBridge(createMockSnapshot("create"));
    const before = await invalid.bootstrap();
    await expect(
      invalid.dispatch(makeProductCommand("CREATE_RETRY", {}, before.revision)),
    ).rejects.toMatchObject({ code: "WRONG_STAGE", retryable: false });
    await expect(invalid.bootstrap()).resolves.toMatchObject({ revision: before.revision });
  });

  it("submits an outfit with the production-shaped durable pending projection", async () => {
    const bridge = new MockGigagochiBridge(createMockSnapshot("dashboard"));
    let snapshot = await bridge.bootstrap();
    const initialExperience = snapshot.pet?.experience ?? 0;
    snapshot = await bridge.dispatch(
      makeProductCommand("DASHBOARD_OPEN_MODE", { mode: "outfit" }, snapshot.revision),
    );
    const command = makeProductCommand(
      "OUTFIT_SUBMIT",
      { prompt: "  красный шарф  " },
      snapshot.revision,
    );

    const submitted = await bridge.dispatch(command);

    expect(submitted.revision).toBe("mock-2");
    expect(submitted.dashboardMode).toBe("idle");
    expect(submitted.pet?.experience).toBe(initialExperience - 200);
    expect(submitted.dashboard?.outfit).toMatchObject({
      draft: "",
      error: null,
      activeRequestKey: null,
      thinking: false,
      pending: {
        requestKey: command.requestKey,
        status: "attached",
        prompt: "красный шарф",
        displayItem: "красный шарф",
        experienceCost: 200,
      },
    });
    expect(submitted.pending.outfit).toEqual({
      requestKey: command.requestKey,
      status: "attached",
      prompt: "красный шарф",
    });
    expect(submitted.dashboard?.reply).toMatchObject({
      source: "transient",
      requestKey: command.requestKey,
    });
    expect(isAppSnapshot(submitted)).toBe(true);
  });

  it("retries the same outfit pending identity without a second experience debit", async () => {
    const base = createMockSnapshot("dashboard");
    const requestKey = "fixture-outfit-request";
    const retryable: AppSnapshot = {
      ...base,
      revision: "mock-4",
      dashboardMode: "outfit",
      dashboard: {
        ...base.dashboard!,
        outfit: {
          ...base.dashboard!.outfit,
          error: "Не удалось создать наряд",
          pending: {
            requestKey,
            status: "retryable",
            prompt: "красный шарф",
            displayItem: "красный шарф",
            experienceCost: 200,
          },
        },
      },
      pending: {
        ...base.pending,
        outfit: { requestKey, status: "retryable", prompt: "красный шарф" },
      },
    };
    const bridge = new MockGigagochiBridge(retryable);

    const retried = await bridge.dispatch(
      makeProductCommand("OUTFIT_RETRY", {}, retryable.revision),
    );

    expect(retried.revision).toBe("mock-5");
    expect(retried.dashboardMode).toBe("idle");
    expect(retried.pet?.experience).toBe(retryable.pet?.experience);
    expect(retried.dashboard?.outfit).toMatchObject({
      error: null,
      activeRequestKey: null,
      thinking: false,
      pending: { requestKey, status: "attached" },
    });
    expect(retried.pending.outfit).toEqual({
      requestKey,
      status: "attached",
      prompt: "красный шарф",
    });
    expect(isAppSnapshot(retried)).toBe(true);
  });

  it("submits travel with a trimmed durable pending projection", async () => {
    const bridge = new MockGigagochiBridge(createMockSnapshot("dashboard"));
    let snapshot = await bridge.bootstrap();
    snapshot = await bridge.dispatch(
      makeProductCommand("DASHBOARD_OPEN_MODE", { mode: "travel" }, snapshot.revision),
    );
    const command = makeProductCommand(
      "TRAVEL_SUBMIT",
      { prompt: "  ночной рынок духов  " },
      snapshot.revision,
    );

    const submitted = await bridge.dispatch(command);

    expect(submitted.revision).toBe("mock-2");
    expect(submitted.dashboardMode).toBe("idle");
    expect(submitted.dashboard?.travel).toMatchObject({
      draft: "",
      error: null,
      activeRequestKey: null,
      thinking: false,
      pending: {
        requestKey: command.requestKey,
        status: "attached",
        prompt: "ночной рынок духов",
      },
    });
    expect(submitted.pending.travel).toEqual({
      requestKey: command.requestKey,
      status: "attached",
      prompt: "ночной рынок духов",
    });
    expect(submitted.dashboard?.reply).toMatchObject({
      source: "transient",
      requestKey: command.requestKey,
    });
    expect(isAppSnapshot(submitted)).toBe(true);
  });

  it("retries the same travel pending identity and clears its error", async () => {
    const base = createMockSnapshot("dashboard");
    const requestKey = "fixture-travel-request";
    const retryable: AppSnapshot = {
      ...base,
      revision: "mock-9",
      dashboardMode: "travel",
      dashboard: {
        ...base.dashboard!,
        travel: {
          ...base.dashboard!.travel,
          error: "Не удалось создать путешествие",
          pending: {
            requestKey,
            status: "pending",
            prompt: "ночной рынок духов",
          },
        },
      },
      pending: {
        ...base.pending,
        travel: { requestKey, status: "pending", prompt: "ночной рынок духов" },
      },
    };
    const bridge = new MockGigagochiBridge(retryable);

    const retried = await bridge.dispatch(
      makeProductCommand("TRAVEL_RETRY", {}, retryable.revision),
    );

    expect(retried.revision).toBe("mock-10");
    expect(retried.dashboardMode).toBe("idle");
    expect(retried.dashboard?.travel).toMatchObject({
      error: null,
      activeRequestKey: null,
      thinking: false,
      pending: { requestKey, status: "attached" },
    });
    expect(retried.pending.travel).toEqual({
      requestKey,
      status: "attached",
      prompt: "ночной рынок духов",
    });
    expect(isAppSnapshot(retried)).toBe(true);
  });

  it("reproduces the fifth-tap happiness reward", async () => {
    const bridge = new MockGigagochiBridge();
    let snapshot = await bridge.bootstrap();
    const initialHappiness = snapshot.pet?.happiness ?? 0;

    for (let tap = 0; tap < 5; tap += 1) {
      snapshot = await bridge.dispatch(
        makeProductCommand("PET_TAP", {}, snapshot.revision),
      );
    }

    expect(snapshot.pet?.petTapProgress).toBe(0);
    expect(snapshot.pet?.happiness).toBe(Math.min(100, initialHappiness + 15));
    expect(snapshot.pet?.message).not.toBe("Приятно!");
    expect(snapshot.petTapFeedback).toMatchObject({
      rewarded: true,
      thanks: "Приятно!",
      visibleMillis: 5_000,
    });
  });

  it("models the native dashboard mode, chat reply, and feed pulse contract", async () => {
    const bridge = new MockGigagochiBridge();
    let snapshot = await bridge.bootstrap();
    expect(snapshot.dashboard?.feed.audioIndex).toBeNull();

    snapshot = await bridge.dispatch(
      makeProductCommand("DASHBOARD_OPEN_MODE", { mode: "chat" }, snapshot.revision),
    );
    expect(snapshot.dashboardMode).toBe("chat");
    snapshot = await bridge.dispatch(
      makeProductCommand("CHAT_SEND", { message: "Привет" }, snapshot.revision),
    );
    expect(snapshot.dashboard?.reply).toMatchObject({
      source: "chat",
      portions: ["Слышал, почему лёд плавает?"],
    });

    snapshot = await bridge.dispatch(
      makeProductCommand("DASHBOARD_CLOSE_MODE", {}, snapshot.revision),
    );
    snapshot = await bridge.dispatch(
      makeProductCommand("DASHBOARD_OPEN_MODE", { mode: "feed" }, snapshot.revision),
    );
    snapshot = await bridge.dispatch(
      makeProductCommand("FEED_CONSUME", { food: "leaf-crunch" }, snapshot.revision),
    );
    expect(snapshot.dashboard?.feed).toMatchObject({
      activeFood: "leaf-crunch",
      audioIndex: 0,
      pulseId: 1,
    });
    expect(snapshot.dashboard?.reply?.portions).toEqual([
      "Мне легче, но это ужасная гадость!!",
    ]);
  });

  it("projects CHAT_RETRY without changing the durable active request key", async () => {
    const initial = createMockSnapshot("dashboard");
    const dashboard = initial.dashboard!;
    const activeRequestKey = "123e4567-e89b-42d3-a456-426614174199";
    const retryable: AppSnapshot = {
      ...initial,
      dashboardMode: "chat",
      dashboard: {
        ...dashboard,
        chat: {
          ...dashboard.chat,
          draft: "Исходное сообщение",
          error: "Не получилось отправить сообщение. Попробуйте ещё раз.",
          activeRequestKey,
          thinking: false,
        },
      },
    };
    const bridge = new MockGigagochiBridge(retryable);

    const snapshot = await bridge.dispatch(
      makeProductCommand("CHAT_RETRY", {}, retryable.revision),
    );

    expect(snapshot.dashboard?.chat).toMatchObject({
      activeRequestKey,
      draft: "",
      error: null,
      thinking: true,
    });
  });
});
