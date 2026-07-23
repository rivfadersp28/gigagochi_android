import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SPEECH_MAX_CONCURRENT_VOICES,
  SPEECH_VOLUME,
  SpeechSequencePlayer,
  type SpeechSequenceDependencies,
  type SpeechVoice,
} from "./speechSequence";

class FakeVoice implements SpeechVoice {
  volume = 1;
  currentTime = 0;
  played = 0;
  paused = 0;
  loaded = 0;
  private listeners = new Map<string, () => void>();

  constructor(
    readonly url: string,
    private readonly rejectPlayback = false,
    private readonly onPlay: (url: string) => void = () => undefined,
  ) {}

  play(): Promise<void> {
    this.played += 1;
    this.onPlay(this.url);
    return this.rejectPlayback ? Promise.reject(new Error("blocked")) : Promise.resolve();
  }

  pause(): void {
    this.paused += 1;
  }

  load(): void {
    this.loaded += 1;
  }

  addEventListener(type: "ended" | "error", listener: () => void): void {
    this.listeners.set(type, listener);
  }
}

function harness(random = () => 0.5, rejectPlayback = false) {
  const voices: FakeVoice[] = [];
  const playedUrls: string[] = [];
  const dependencies: SpeechSequenceDependencies = {
    now: Date.now,
    random,
    createVoice: (url) => {
      const voice = new FakeVoice(url, rejectPlayback, (playedUrl) => playedUrls.push(playedUrl));
      voices.push(voice);
      return voice;
    },
    setTimer: (callback, delayMillis) => window.setTimeout(callback, delayMillis),
    clearTimer: (timer) => window.clearTimeout(timer),
  };
  return { voices, playedUrls, player: new SpeechSequencePlayer(dependencies) };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("speech reveal sequence", () => {
  it("plays non-repeating samples at volume .32 and stops every stream at the exact duration", () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const { player, voices, playedUrls } = harness();

    expect(voices).toHaveLength(32);
    expect(voices.every((voice) => voice.loaded === 1)).toBe(true);
    const preloadedCount = voices.length;
    player.start(100);
    expect(voices.filter((voice) => voice.played > 0)).toHaveLength(1);
    actTime(96);
    const played = voices.filter((voice) => voice.played > 0);
    expect(played).toHaveLength(3);
    expect(voices).toHaveLength(preloadedCount);
    expect(voices.every((voice) => voice.volume === SPEECH_VOLUME)).toBe(true);
    expect(playedUrls[0]).not.toBe(playedUrls[1]);
    expect(playedUrls[1]).not.toBe(playedUrls[2]);
    expect(played.some((voice) => voice.paused > 0)).toBe(false);

    actTime(4);
    expect(played.every((voice) => voice.paused === 1 && voice.currentTime === 0)).toBe(true);
    actTime(1_000);
    expect(voices).toHaveLength(preloadedCount);
  });

  it("caps the pool at eight concurrent voices and cancels pending ticks", () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const { player, voices, playedUrls } = harness(() => 0);
    const preloadedCount = voices.length;
    player.start(1_000);

    actTime(42 * SPEECH_MAX_CONCURRENT_VOICES);
    const played = voices.filter((voice) => voice.played > 0);
    expect(playedUrls.length).toBeGreaterThan(SPEECH_MAX_CONCURRENT_VOICES);
    expect(played[0]?.paused).toBe(1);

    player.stop();
    actTime(1_000);
    expect(voices).toHaveLength(preloadedCount);
    expect(played.every((voice) => voice.paused >= 1)).toBe(true);
  });

  it("silently tears down a voice when browser playback rejects", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const { player, voices } = harness(() => 0.5, true);
    player.start(100);
    await Promise.resolve();
    expect(voices.find((voice) => voice.played > 0)?.paused).toBe(1);
    player.stop();
  });
});

function actTime(millis: number): void {
  vi.advanceTimersByTime(millis);
}
