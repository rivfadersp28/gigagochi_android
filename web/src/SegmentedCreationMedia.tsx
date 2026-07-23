import { useEffect, useRef, useState } from "react";
import type { CreateSnapshot } from "./contracts";
import { motionConfig } from "./motionConfig";

type CreationPhase = CreateSnapshot["phase"];

const SEGMENTS = Object.fromEntries(
  Object.entries(motionConfig.creation.timeline.segments).map(([phase, segment]) => [
    phase,
    {
      start: segment.startFrame / motionConfig.creation.timeline.framesPerSecond,
      end: segment.endFrame / motionConfig.creation.timeline.framesPerSecond,
      loop: segment.loop,
    },
  ]),
) as Record<CreationPhase, { start: number; end: number; loop: boolean }>;

interface VideoFrameMetadata {
  mediaTime: number;
}

interface VideoFrameCallbackApi {
  requestVideoFrameCallback(
    callback: (now: number, metadata: VideoFrameMetadata) => void,
  ): number;
  cancelVideoFrameCallback?(handle: number): void;
}

interface SegmentedCreationMediaProps {
  phase: CreationPhase;
  reducedMotion: boolean;
  dimmed: boolean;
  onTransitionComplete(): void;
}

function posterForPhase(phase: CreationPhase): string {
  return phase === "initial" ? "/res/clouds_empty.png" : "/res/clouds_formed.png";
}

export function SegmentedCreationMedia({
  phase,
  reducedMotion,
  dimmed,
  onTransitionComplete,
}: SegmentedCreationMediaProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const completionRef = useRef(onTransitionComplete);
  const [posterState, setPosterState] = useState<{ phase: CreationPhase; visible: boolean }>({
    phase,
    visible: true,
  });
  completionRef.current = onTransitionComplete;

  useEffect(() => {
    if (reducedMotion) return;
    const video = videoRef.current;
    if (!video) return;

    const segment = SEGMENTS[phase];
    const frameApi = video as HTMLVideoElement & Partial<VideoFrameCallbackApi>;
    const hasVideoFrameCallback = typeof frameApi.requestVideoFrameCallback === "function";
    let active = true;
    let configured = false;
    let failed = false;
    let transitionCompleted = false;
    let videoFrameHandle: number | null = null;
    let animationFrameHandle: number | null = null;
    let fallbackFramesInsideSegment = 0;

    setPosterState({ phase, visible: true });

    const setPosterVisible = (visible: boolean) => {
      if (!active) return;
      setPosterState({ phase, visible });
    };

    const play = () => {
      void video.play().catch(() => undefined);
    };

    const isInsideSegment = (mediaTime: number) => (
      Number.isFinite(mediaTime)
      && mediaTime >= segment.start
      && mediaTime < segment.end
    );

    const reachBoundary = (mediaTime: number): boolean => {
      if (!active || failed || transitionCompleted || mediaTime < segment.end) return false;
      if (segment.loop) {
        video.currentTime = segment.start;
        fallbackFramesInsideSegment = 0;
        play();
        return true;
      }

      transitionCompleted = true;
      video.pause();
      video.currentTime = segment.end;
      completionRef.current();
      return true;
    };

    const onVideoFrame = (_now: number, metadata: VideoFrameMetadata) => {
      videoFrameHandle = null;
      if (!active || failed || transitionCompleted) return;

      const mediaTime = Number.isFinite(metadata.mediaTime)
        ? metadata.mediaTime
        : video.currentTime;
      if (isInsideSegment(mediaTime)) setPosterVisible(false);
      reachBoundary(Math.max(mediaTime, video.currentTime));
      if (active && !transitionCompleted) scheduleVideoFrame();
    };

    const onAnimationFrame = () => {
      animationFrameHandle = null;
      if (!active || failed || transitionCompleted) return;

      const mediaTime = video.currentTime;
      if (
        video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
        && !video.seeking
        && isInsideSegment(mediaTime)
      ) {
        // Without requestVideoFrameCallback there is no rendered-frame signal.
        // Waiting for two paint opportunities avoids exposing a retained frame
        // from the previous segment while the seek is settling.
        fallbackFramesInsideSegment += 1;
        if (fallbackFramesInsideSegment >= 2) setPosterVisible(false);
      } else {
        fallbackFramesInsideSegment = 0;
      }
      reachBoundary(mediaTime);
      if (active && !transitionCompleted) scheduleAnimationFrame();
    };

    function scheduleVideoFrame() {
      if (!active || transitionCompleted || videoFrameHandle !== null) return;
      videoFrameHandle = frameApi.requestVideoFrameCallback?.(onVideoFrame) ?? null;
    }

    function scheduleAnimationFrame() {
      if (!active || transitionCompleted || animationFrameHandle !== null) return;
      animationFrameHandle = window.requestAnimationFrame(onAnimationFrame);
    }

    const configureSegment = () => {
      if (
        !active
        || failed
        || configured
        || video.readyState < HTMLMediaElement.HAVE_METADATA
      ) return;
      configured = true;
      fallbackFramesInsideSegment = 0;
      video.currentTime = segment.start;
      play();
    };

    const onTimeUpdate = () => {
      reachBoundary(video.currentTime);
    };

    const onError = () => {
      failed = true;
      setPosterVisible(true);
    };

    video.addEventListener("loadedmetadata", configureSegment);
    video.addEventListener("timeupdate", onTimeUpdate);
    video.addEventListener("error", onError);
    configureSegment();
    if (hasVideoFrameCallback) scheduleVideoFrame();
    else scheduleAnimationFrame();

    return () => {
      active = false;
      video.removeEventListener("loadedmetadata", configureSegment);
      video.removeEventListener("timeupdate", onTimeUpdate);
      video.removeEventListener("error", onError);
      if (videoFrameHandle !== null) {
        frameApi.cancelVideoFrameCallback?.(videoFrameHandle);
      }
      if (animationFrameHandle !== null) {
        window.cancelAnimationFrame(animationFrameHandle);
      }
    };
  }, [phase, reducedMotion]);

  const poster = posterForPhase(phase);
  const mediaClass = dimmed ? "creation-media creation-media--dimmed" : "creation-media";
  const showPoster = reducedMotion || posterState.phase !== phase || posterState.visible;

  return (
    <div className={mediaClass} aria-hidden="true">
      {reducedMotion ? (
        <img className="creation-media__visual" src={poster} alt="" />
      ) : (
        <>
          <video
            ref={videoRef}
            className="creation-media__visual"
            src="/assets/media/clouds_creation_timeline.mp4"
            poster={poster}
            muted
            playsInline
            autoPlay
            preload="auto"
          />
          {showPoster ? (
            <img
              className="creation-media__visual"
              src={poster}
              alt=""
              data-testid="creation-phase-poster"
            />
          ) : null}
        </>
      )}
      <img className="creation-media__filter" src="/res/video_filter_normal.webp" alt="" />
    </div>
  );
}
