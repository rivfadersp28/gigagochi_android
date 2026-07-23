import { useEffect, useState } from "react";
import { isBundledMediaRef } from "./eventHistoryModel";

interface BundledStoryMediaProps {
  posterRef: string | null;
  videoRef: string | null;
  playVideo: boolean;
  reducedMotion: boolean;
  foreground?: boolean;
  forcePoster?: boolean;
  description: string;
  className?: string;
}

export function BundledStoryMedia({
  posterRef,
  videoRef,
  playVideo,
  reducedMotion,
  foreground = true,
  forcePoster = false,
  description,
  className,
}: BundledStoryMediaProps) {
  const [firstFrameRendered, setFirstFrameRendered] = useState(false);
  const safePosterRef = isBundledMediaRef(posterRef) ? posterRef : null;
  const safeVideoRef = isBundledMediaRef(videoRef) ? videoRef : null;
  const canPlay = Boolean(
    safeVideoRef && playVideo && foreground && !reducedMotion && !forcePoster,
  );

  useEffect(() => {
    setFirstFrameRendered(false);
  }, [safeVideoRef, canPlay]);

  const mediaClass = ["event-story-media", className].filter(Boolean).join(" ");
  const showPoster = !canPlay || !firstFrameRendered || forcePoster;
  return (
    <div className={mediaClass} role="img" aria-label={description}>
      {canPlay ? (
        <video
          className="event-story-media__video"
          src={safeVideoRef ?? undefined}
          muted
          loop
          playsInline
          autoPlay
          preload="auto"
          aria-hidden="true"
          onPlaying={() => setFirstFrameRendered(true)}
          onError={() => setFirstFrameRendered(false)}
        />
      ) : null}
      {safePosterRef ? (
        <img
          className={showPoster
            ? "event-story-media__poster"
            : "event-story-media__poster event-story-media__poster--hidden"}
          src={safePosterRef}
          alt=""
          aria-hidden="true"
        />
      ) : null}
    </div>
  );
}
