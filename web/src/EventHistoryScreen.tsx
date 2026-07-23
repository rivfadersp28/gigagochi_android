import {
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type RefCallback,
  type RefObject,
} from "react";
import { BundledStoryMedia } from "./BundledStoryMedia";
import { ContextualStoryBack } from "./ContextualStoryBack";
import type {
  EventHistoryItemData,
  LocalScheduledStoryData,
  TravelVideoAssetData,
  TravelVideoShareResult,
} from "./EventStoryTypes";
import {
  closestCenterEventKey,
  createEventHistoryData,
  initialEventScrollTop,
  travelEventCaption,
  travelEventKey,
} from "./eventHistoryModel";
import { usePressMotion } from "./usePressMotion";
import "./eventStory.css";

interface EventHistoryScreenProps {
  stories: LocalScheduledStoryData[];
  travelVideos?: TravelVideoAssetData[];
  reducedMotion: boolean;
  foreground?: boolean;
  initialFocusTravelRequestKey?: string | null;
  onShare(asset: TravelVideoAssetData): Promise<TravelVideoShareResult> | TravelVideoShareResult;
  onHelp(item: LocalScheduledStoryData): void;
  onBack(): void;
}

function useClosestCenterEvent(
  eventKeys: string[],
  rootRef: RefObject<HTMLDivElement | null>,
  itemRefs: RefObject<Map<string, HTMLElement>>,
): string | null {
  const [activeKey, setActiveKey] = useState<string | null>(null);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    let animationFrame: number | null = null;
    const update = () => {
      animationFrame = null;
      const rootBounds = root.getBoundingClientRect();
      const viewportStart = rootBounds.bottom > rootBounds.top ? rootBounds.top : 0;
      const viewportEnd = rootBounds.bottom > rootBounds.top
        ? rootBounds.bottom
        : window.innerHeight;
      const candidates = eventKeys.flatMap((key) => {
        const element = itemRefs.current.get(key);
        if (!element) return [];
        const bounds = element.getBoundingClientRect();
        return [{ key, start: bounds.top, end: bounds.bottom }];
      });
      setActiveKey(closestCenterEventKey(viewportStart, viewportEnd, candidates));
    };
    const scheduleUpdate = () => {
      if (animationFrame !== null) return;
      animationFrame = window.requestAnimationFrame(update);
    };

    update();
    root.addEventListener("scroll", scheduleUpdate, { passive: true });
    window.addEventListener("resize", scheduleUpdate);
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(scheduleUpdate);
    observer?.observe(root);
    itemRefs.current.forEach((element) => observer?.observe(element));
    return () => {
      root.removeEventListener("scroll", scheduleUpdate);
      window.removeEventListener("resize", scheduleUpdate);
      observer?.disconnect();
      if (animationFrame !== null) window.cancelAnimationFrame(animationFrame);
    };
  }, [eventKeys, itemRefs, rootRef]);

  return activeKey;
}

function TiltedEventButton({
  label,
  accessibilityLabel,
  width,
  disabled = false,
  reducedMotion,
  onClick,
}: {
  label: string;
  accessibilityLabel: string;
  width: string;
  disabled?: boolean;
  reducedMotion: boolean;
  onClick(): void;
}) {
  const press = usePressMotion<HTMLButtonElement>({
    disabled,
    reducedMotion,
    kind: "eventSpring",
  });
  return (
    <div className="event-history__paper-button-tilt" style={{ width }}>
      <button
        ref={press.ref}
        className="event-history__paper-button"
        type="button"
        aria-label={accessibilityLabel}
        disabled={disabled}
        data-press-phase={press.phase}
        onPointerDown={press.onPointerDown}
        onPointerUp={press.onPointerUp}
        onPointerCancel={press.onPointerCancel}
        onPointerLeave={press.onPointerLeave}
        onKeyDown={press.onKeyDown}
        onKeyUp={press.onKeyUp}
        onBlur={press.onBlur}
        onClick={onClick}
      >
        {label}
      </button>
    </div>
  );
}

function TravelVideoEventCard({
  asset,
  playVideo,
  reducedMotion,
  foreground,
  onShare,
}: {
  asset: TravelVideoAssetData;
  playVideo: boolean;
  reducedMotion: boolean;
  foreground: boolean;
  onShare(asset: TravelVideoAssetData): Promise<TravelVideoShareResult> | TravelVideoShareResult;
}) {
  const [sharing, setSharing] = useState(false);
  const caption = travelEventCaption(asset);
  const share = async () => {
    if (sharing) return;
    setSharing(true);
    try {
      await onShare(asset);
    } catch {
      // The Android share handler owns native Toast/error presentation.
    } finally {
      setSharing(false);
    }
  };
  return (
    <article className="event-history__card event-history__card--travel">
      <BundledStoryMedia
        posterRef={asset.imageRef}
        videoRef={asset.videoRef}
        playVideo={playVideo}
        reducedMotion={reducedMotion}
        foreground={foreground}
        description={`Видео путешествия: ${caption}`}
        className="event-history__media event-history__media--travel"
      />
      <h2 className="event-history__caption">{caption}</h2>
      <TiltedEventButton
        label={sharing ? "Подготавливаю…" : "Показать друзьям"}
        accessibilityLabel={sharing ? "Подготовка видео" : "Поделиться видео"}
        width="271.328px"
        disabled={sharing}
        reducedMotion={reducedMotion}
        onClick={() => void share()}
      />
    </article>
  );
}

function UnansweredStoryEventCard({
  item,
  playVideo,
  reducedMotion,
  foreground,
  onHelp,
}: {
  item: LocalScheduledStoryData;
  playVideo: boolean;
  reducedMotion: boolean;
  foreground: boolean;
  onHelp(): void;
}) {
  return (
    <article className="event-history__card event-history__card--story">
      <BundledStoryMedia
        posterRef={item.story.imageRef}
        videoRef={item.story.videoRef}
        playVideo={playVideo}
        reducedMotion={reducedMotion}
        foreground={foreground}
        description={`Видео события: ${item.story.title}`}
        className="event-history__media"
      />
      <h2 className="event-history__caption">{item.story.text.slice(0, 180)}</h2>
      <TiltedEventButton
        label="Помочь"
        accessibilityLabel="Помочь"
        width="150px"
        reducedMotion={reducedMotion}
        onClick={onHelp}
      />
    </article>
  );
}

function AnsweredStoryEventCard({
  item,
  playVideo,
  reducedMotion,
  foreground,
}: {
  item: LocalScheduledStoryData;
  playVideo: boolean;
  reducedMotion: boolean;
  foreground: boolean;
}) {
  const result = item.story.result;
  if (!result) return null;
  return (
    <article className="event-history__card event-history__card--result">
      <BundledStoryMedia
        posterRef={item.story.resultImageRef ?? item.story.imageRef}
        videoRef={item.story.resultVideoRef ?? item.story.videoRef}
        playVideo={playVideo}
        reducedMotion={reducedMotion}
        foreground={foreground}
        description={`Итог события: ${item.story.title}`}
        className="event-history__media"
      />
      <div className="event-history__result-copy">
        <p>{result.text}</p>
        <p>{result.consequence}</p>
        <p className="event-history__result-reaction">{result.reaction}</p>
        <div
          className="event-history__reward"
          aria-label={`Получено ${result.experienceGained} монет`}
        >
          <img src="/res/xp_coin.svg" alt="" />
          <strong>+{result.experienceGained}</strong>
        </div>
      </div>
    </article>
  );
}

function EventCard({
  item,
  activeKey,
  reducedMotion,
  foreground,
  onShare,
  onHelp,
}: {
  item: EventHistoryItemData;
  activeKey: string | null;
  reducedMotion: boolean;
  foreground: boolean;
  onShare(asset: TravelVideoAssetData): Promise<TravelVideoShareResult> | TravelVideoShareResult;
  onHelp(item: LocalScheduledStoryData): void;
}) {
  if (item.kind === "travel") {
    return (
      <TravelVideoEventCard
        asset={item.asset}
        playVideo={item.key === activeKey}
        reducedMotion={reducedMotion}
        foreground={foreground}
        onShare={onShare}
      />
    );
  }
  return item.answered ? (
    <AnsweredStoryEventCard
      item={item.item}
      playVideo={item.key === activeKey}
      reducedMotion={reducedMotion}
      foreground={foreground}
    />
  ) : (
    <UnansweredStoryEventCard
      item={item.item}
      playVideo={item.key === activeKey}
      reducedMotion={reducedMotion}
      foreground={foreground}
      onHelp={() => onHelp(item.item)}
    />
  );
}

export function EventHistoryScreen({
  stories,
  travelVideos = [],
  reducedMotion,
  foreground = true,
  initialFocusTravelRequestKey = null,
  onShare,
  onHelp,
  onBack,
}: EventHistoryScreenProps) {
  const history = useMemo(
    () => createEventHistoryData(stories, travelVideos),
    [stories, travelVideos],
  );
  const rootRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef(new Map<string, HTMLElement>());
  const eventKeys = useMemo(() => history.items.map((item) => item.key), [history.items]);
  const activeKey = useClosestCenterEvent(eventKeys, rootRef, itemRefs);
  const refs = useMemo(() => new Map<string, RefCallback<HTMLElement>>(
    eventKeys.map((key) => [key, (element) => {
      if (element) itemRefs.current.set(key, element);
      else itemRefs.current.delete(key);
    }]),
  ), [eventKeys]);

  useLayoutEffect(() => {
    if (!initialFocusTravelRequestKey) return;
    const root = rootRef.current;
    const element = itemRefs.current.get(travelEventKey(initialFocusTravelRequestKey));
    if (!root || !element) return;
    const paddingTop = Number.parseFloat(window.getComputedStyle(root).paddingTop) || 0;
    root.scrollTop = initialEventScrollTop(
      element.offsetTop,
      paddingTop,
      root.scrollHeight - root.clientHeight,
    );
  }, [history.items, initialFocusTravelRequestKey]);

  return (
    <main className={reducedMotion
      ? "event-history event-history--reduced-motion"
      : "event-history"}
      tabIndex={-1}
    >
      <div
        ref={rootRef}
        className="event-history__scroll"
        role="region"
        aria-label="История событий"
        tabIndex={0}
      >
        <h1>События</h1>
        {history.isEmpty ? <p className="event-history__empty">Пока событий нет</p> : null}
        {history.items.map((item) => (
          <div
            key={item.key}
            ref={refs.get(item.key)}
            className="event-history__item"
            data-event-key={item.key}
            data-event-active={item.key === activeKey ? "true" : "false"}
          >
            <EventCard
              item={item}
              activeKey={activeKey}
              reducedMotion={reducedMotion}
              foreground={foreground}
              onShare={onShare}
              onHelp={onHelp}
            />
          </div>
        ))}
      </div>
      <ContextualStoryBack onClick={onBack} />
    </main>
  );
}
