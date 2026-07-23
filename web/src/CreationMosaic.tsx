import { useEffect, useRef, useState } from "react";
import { motionConfig } from "./motionConfig";

const MOSAIC_FRAMES = motionConfig.creation.mosaic.frames;

interface CreationMosaicProps {
  reducedMotion: boolean;
}

function loadPixelFrame(
  source: (typeof MOSAIC_FRAMES)[number],
  pendingImages: HTMLImageElement[],
): Promise<HTMLCanvasElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    pendingImages.push(image);
    image.decoding = "async";
    image.onload = () => {
      const pixelCanvas = document.createElement("canvas");
      pixelCanvas.width = source.edgePixels;
      pixelCanvas.height = source.edgePixels;
      const context = pixelCanvas.getContext("2d");
      if (!context) {
        reject(new Error("Canvas 2D context is unavailable"));
        return;
      }
      context.imageSmoothingEnabled = false;
      context.drawImage(image, 0, 0, source.edgePixels, source.edgePixels);
      resolve(pixelCanvas);
    };
    image.onerror = () => reject(new Error(`Unable to load mosaic frame: ${source.src}`));
    image.src = source.src;
  });
}

export function CreationMosaic({ reducedMotion }: CreationMosaicProps) {
  const canvasRefs = useRef<Array<HTMLCanvasElement | null>>([]);
  const [processedFrames, setProcessedFrames] = useState<readonly HTMLCanvasElement[] | null>(null);
  const [frame, setFrame] = useState(0);
  const [crossfadeEnabled, setCrossfadeEnabled] = useState(false);

  useEffect(() => {
    if (reducedMotion) {
      setProcessedFrames(null);
      setFrame(0);
      setCrossfadeEnabled(false);
      return;
    }

    let active = true;
    const pendingImages: HTMLImageElement[] = [];
    setProcessedFrames(null);
    setFrame(0);
    setCrossfadeEnabled(false);
    void Promise.all(MOSAIC_FRAMES.map((source) => loadPixelFrame(source, pendingImages)))
      .then((frames) => {
        if (active) setProcessedFrames(frames);
      })
      .catch(() => {
        if (active) setProcessedFrames(null);
      });

    return () => {
      active = false;
      pendingImages.forEach((image) => {
        image.onload = null;
        image.onerror = null;
      });
    };
  }, [reducedMotion]);

  useEffect(() => {
    if (reducedMotion || !processedFrames) return;
    processedFrames.forEach((source, index) => {
      const canvas = canvasRefs.current[index];
      const context = canvas?.getContext("2d");
      if (!canvas || !context) return;
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.imageSmoothingEnabled = false;
      context.drawImage(source, 0, 0, canvas.width, canvas.height);
    });
  }, [processedFrames, reducedMotion]);

  useEffect(() => {
    if (reducedMotion || !processedFrames) return;
    const timer = window.setInterval(() => {
      setCrossfadeEnabled(true);
      setFrame((current) => (current + 1) % MOSAIC_FRAMES.length);
    }, motionConfig.creation.mosaic.frameDurationMillis);
    return () => window.clearInterval(timer);
  }, [processedFrames, reducedMotion]);

  if (reducedMotion) {
    return (
      <img
        className="creation-mosaic__still"
        src="/res/main_pet.png"
        alt=""
        style={{ animation: "none", imageRendering: "auto" }}
      />
    );
  }

  return (
    <div
      data-testid="creation-mosaic-layers"
      style={{ position: "relative", width: "100%", height: "100%" }}
    >
      {MOSAIC_FRAMES.map((source, index) => (
        <canvas
          key={`${source.src}:${source.edgePixels}`}
          ref={(element) => { canvasRefs.current[index] = element; }}
          className="creation-mosaic__canvas"
          width={motionConfig.creation.mosaic.canvasSizePixels}
          height={motionConfig.creation.mosaic.canvasSizePixels}
          aria-hidden="true"
          data-frame={index}
          data-source={source.src}
          data-edge={source.edgePixels}
          data-active={processedFrames !== null && frame === index ? "true" : "false"}
          style={{
            position: "absolute",
            inset: 0,
            opacity: processedFrames !== null && frame === index ? 1 : 0,
            transition: processedFrames && crossfadeEnabled
              ? `opacity ${motionConfig.creation.mosaic.crossfadeDurationMillis}ms ${motionConfig.easing.fastOutSlowIn.css}`
              : "none",
            animation: "none",
            imageRendering: "pixelated",
            pointerEvents: "none",
          }}
        />
      ))}
    </div>
  );
}
