import { motionConfig } from "./motionConfig";

export const SPEECH_SAMPLE_URLS = [
  "/res/speech_1.wav",
  "/res/speech_2.wav",
  "/res/speech_3.wav",
  "/res/speech_4.wav",
] as const;

export const SPEECH_VOLUME = 0.32;
export const SPEECH_MAX_CONCURRENT_VOICES = 8;
export const SPEECH_POOL_SIZE_PER_SAMPLE = SPEECH_MAX_CONCURRENT_VOICES;
export const SPEECH_INTERVAL_MILLIS = motionConfig.dialogue.speechIntervalMillis;
export const SPEECH_INTERVAL_JITTER_MILLIS = motionConfig.dialogue.speechIntervalJitterMillis;

export interface SpeechVoice {
  volume: number;
  currentTime: number;
  play(): Promise<void> | void;
  pause(): void;
  load?(): void;
  addEventListener(type: "ended" | "error", listener: () => void, options?: { once?: boolean }): void;
}

interface SpeechVoiceSlot {
  readonly voice: SpeechVoice;
  readonly sampleIndex: number;
  generation: number;
}

export interface SpeechSequenceDependencies {
  now(): number;
  random(): number;
  createVoice(url: string): SpeechVoice;
  setTimer(callback: () => void, delayMillis: number): number;
  clearTimer(timer: number): void;
}

function normalizedRandom(random: () => number): number {
  return Math.min(0.999_999_999, Math.max(0, random()));
}

export class SpeechSequencePlayer {
  private readonly voicePools: SpeechVoiceSlot[][];
  private readonly activeVoices = new Map<SpeechVoiceSlot, number>();
  private tickTimer: number | null = null;
  private stopTimer: number | null = null;
  private startedAt = 0;
  private durationMillis = 0;
  private previousIndex = -1;
  private running = false;

  constructor(private readonly dependencies: SpeechSequenceDependencies) {
    this.voicePools = SPEECH_SAMPLE_URLS.map((url, sampleIndex) => {
      const pool: SpeechVoiceSlot[] = [];
      for (let index = 0; index < SPEECH_POOL_SIZE_PER_SAMPLE; index += 1) {
        try {
          const voice = dependencies.createVoice(url);
          voice.volume = SPEECH_VOLUME;
          voice.load?.();
          pool.push({ voice, sampleIndex, generation: 0 });
        } catch {
          // A missing sample must not make dialogue interaction unusable.
        }
      }
      return pool;
    });
  }

  start(durationMillis: number): void {
    this.stop();
    if (!Number.isFinite(durationMillis) || durationMillis <= 0) return;
    this.durationMillis = durationMillis;
    this.startedAt = this.dependencies.now();
    this.previousIndex = -1;
    this.running = true;
    this.stopTimer = this.dependencies.setTimer(() => this.stop(), durationMillis);
    this.tick();
  }

  stop(): void {
    this.running = false;
    if (this.tickTimer !== null) this.dependencies.clearTimer(this.tickTimer);
    if (this.stopTimer !== null) this.dependencies.clearTimer(this.stopTimer);
    this.tickTimer = null;
    this.stopTimer = null;
    for (const slot of this.activeVoices.keys()) this.stopVoice(slot);
    this.activeVoices.clear();
  }

  private tick = (): void => {
    if (!this.running) return;
    const elapsed = this.dependencies.now() - this.startedAt;
    if (elapsed >= this.durationMillis) {
      this.stop();
      return;
    }

    this.playNextVoice();
    const jitterSteps = SPEECH_INTERVAL_JITTER_MILLIS * 2 + 1;
    const jitter = Math.floor(normalizedRandom(this.dependencies.random) * jitterSteps) -
      SPEECH_INTERVAL_JITTER_MILLIS;
    const delay = Math.max(1, SPEECH_INTERVAL_MILLIS + jitter);
    this.tickTimer = this.dependencies.setTimer(this.tick, delay);
  };

  private playNextVoice(): void {
    const candidates = SPEECH_SAMPLE_URLS.map((_, index) => index)
      .filter((index) => index !== this.previousIndex);
    const candidateIndex = Math.floor(normalizedRandom(this.dependencies.random) * candidates.length);
    const index = candidates[candidateIndex] ?? 0;
    this.previousIndex = index;

    while (this.activeVoices.size >= SPEECH_MAX_CONCURRENT_VOICES) {
      const oldest = this.activeVoices.keys().next().value;
      if (!oldest) break;
      this.activeVoices.delete(oldest);
      this.stopVoice(oldest);
    }

    const slot = this.voicePools[index]?.find((candidate) => !this.activeVoices.has(candidate));
    if (!slot) return;
    slot.generation += 1;
    const generation = slot.generation;
    const release = () => {
      if (this.activeVoices.get(slot) === generation) this.activeVoices.delete(slot);
    };
    slot.voice.addEventListener("ended", release, { once: true });
    slot.voice.addEventListener("error", release, { once: true });
    this.activeVoices.set(slot, generation);
    try {
      slot.voice.currentTime = 0;
      const playback = slot.voice.play();
      if (playback && typeof playback.catch === "function") {
        void playback.catch(() => {
          release();
          this.stopVoice(slot);
        });
      }
    } catch {
      release();
      this.stopVoice(slot);
    }
  }

  private stopVoice(slot: SpeechVoiceSlot): void {
    try {
      slot.voice.pause();
      slot.voice.currentTime = 0;
    } catch {
      // Media teardown is best effort; a detached WebView can reject currentTime changes.
    }
  }
}

export function createBrowserSpeechSequence(): SpeechSequencePlayer | null {
  if (import.meta.env.MODE === "test" || typeof Audio === "undefined") return null;
  return new SpeechSequencePlayer({
    now: () => performance.now(),
    random: Math.random,
    createVoice: (url) => {
      const audio = new Audio(url);
      audio.preload = "auto";
      return audio;
    },
    setTimer: (callback, delayMillis) => window.setTimeout(callback, delayMillis),
    clearTimer: (timer) => window.clearTimeout(timer),
  });
}
