import { useEffect, useRef, useState, type RefObject } from "react";
import type { DashboardMode, PetMediaSnapshot } from "./contracts";

type VideoFrameElement = HTMLVideoElement;

interface DashboardSceneMediaProps {
  media: PetMediaSnapshot;
  mode: DashboardMode;
  reducedMotion: boolean;
  foreground?: boolean;
  videoRef: RefObject<HTMLVideoElement | null>;
}

export function DashboardSceneMedia(props: DashboardSceneMediaProps) {
  const identity = [
    props.media.videoRef ?? "",
    props.media.posterRef ?? "",
    String(props.reducedMotion),
  ].join("\u0000");
  return <DashboardSceneMediaInstance key={identity} {...props} />;
}

function DashboardSceneMediaInstance({
  media,
  mode,
  reducedMotion,
  foreground = true,
  videoRef,
}: DashboardSceneMediaProps) {
  const fixturePoster = media.videoRef?.startsWith("/assets/")
    ? "/res/test_pet_poster.png"
    : null;
  const posterRef = media.posterRef ?? fixturePoster;
  const videoEnabled = Boolean(media.videoRef) && !reducedMotion;
  const [firstFrameRendered, setFirstFrameRendered] = useState(false);
  const [mediaFailed, setMediaFailed] = useState(false);
  const [retryToken, setRetryToken] = useState(0);
  const pendingFrameCallback = useRef<{ video: VideoFrameElement; handle: number } | null>(null);
  const wasForeground = useRef(foreground);

  useEffect(() => () => {
    const pending = pendingFrameCallback.current;
    if (pending) pending.video.cancelVideoFrameCallback?.(pending.handle);
    pendingFrameCallback.current = null;
  }, []);

  useEffect(() => {
    const previous = wasForeground.current;
    wasForeground.current = foreground;
    const video = videoRef.current;
    if (!videoEnabled || !video) return;
    if (!foreground) {
      video.pause();
    } else if (!previous) {
      void video.play().catch(() => undefined);
    }
  }, [foreground, retryToken, videoEnabled, videoRef]);

  const revealVideoAfterRenderedFrame = (video: VideoFrameElement) => {
    if (pendingFrameCallback.current) return;
    if (video.requestVideoFrameCallback) {
      const handle = video.requestVideoFrameCallback(() => {
        pendingFrameCallback.current = null;
        setMediaFailed(false);
        setFirstFrameRendered(true);
      });
      pendingFrameCallback.current = { video, handle };
      return;
    }
    setMediaFailed(false);
    setFirstFrameRendered(true);
  };

  const shifted = mode === "chat" || mode === "outfit" || mode === "travel";
  return (
    <div className={`dashboard-media${shifted ? " dashboard-media--ime-shifted" : ""}`}>
      {videoEnabled ? (
        <video
          key={retryToken}
          ref={videoRef}
          className={firstFrameRendered ? "scene-video scene-video--ready" : "scene-video"}
          src={media.videoRef ?? undefined}
          muted
          playsInline
          autoPlay={foreground}
          loop
          aria-hidden="true"
          onPlaying={(event) => revealVideoAfterRenderedFrame(event.currentTarget)}
          onError={() => {
            const pending = pendingFrameCallback.current;
            if (pending) pending.video.cancelVideoFrameCallback?.(pending.handle);
            pendingFrameCallback.current = null;
            setMediaFailed(true);
            setFirstFrameRendered(false);
          }}
        />
      ) : null}
      {posterRef ? (
        <img
          key={`${posterRef}:${retryToken}`}
          className={firstFrameRendered && !mediaFailed
            ? "scene-poster scene-poster--hidden"
            : "scene-poster"}
          src={posterRef}
          alt=""
          aria-hidden="true"
          onError={() => setMediaFailed(true)}
        />
      ) : null}
      <img
        className="scene-filter"
        src="/res/video_filter_normal.webp"
        alt=""
        aria-hidden="true"
      />
      {mediaFailed ? (
        <button
          className="scene-media-retry"
          type="button"
          onClick={() => {
            const pending = pendingFrameCallback.current;
            if (pending) pending.video.cancelVideoFrameCallback?.(pending.handle);
            pendingFrameCallback.current = null;
            setFirstFrameRendered(false);
            setMediaFailed(false);
            setRetryToken((current) => current + 1);
          }}
        >
          Повторить медиа
        </button>
      ) : null}
    </div>
  );
}
