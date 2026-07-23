import {
  type CSSProperties,
  type HTMLAttributes,
  type PropsWithChildren,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

const REFERENCE_WIDTH = 402;
const REFERENCE_HEIGHT = 874;

export interface ReferencePlaneMetrics {
  referenceTopRoot: number;
  scale: number;
  viewportWidth: number;
  viewportHeight: number;
}

interface ReferencePlaneProps extends HTMLAttributes<HTMLDivElement> {
  onMetricsChange?(metrics: ReferencePlaneMetrics): void;
}

export function ReferencePlane({
  children,
  className,
  onMetricsChange,
  ...planeProps
}: PropsWithChildren<ReferencePlaneProps>) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);

  useLayoutEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const update = () => {
      const bounds = host.getBoundingClientRect();
      const viewportWidth = bounds.width > 0 ? bounds.width : REFERENCE_WIDTH;
      const viewportHeight = bounds.height > 0 ? bounds.height : REFERENCE_HEIGHT;
      const nextScale = Math.max(
        viewportWidth / REFERENCE_WIDTH,
        viewportHeight / REFERENCE_HEIGHT,
      );
      setScale(nextScale);
      onMetricsChange?.({
        referenceTopRoot: bounds.top,
        scale: nextScale,
        viewportWidth,
        viewportHeight,
      });
    };
    update();
    const ResizeObserverConstructor = (
      window as Window & { ResizeObserver?: typeof ResizeObserver }
    ).ResizeObserver;
    const visualViewport = window.visualViewport;
    visualViewport?.addEventListener("resize", update);
    visualViewport?.addEventListener("scroll", update);
    if (ResizeObserverConstructor) {
      const observer = new ResizeObserverConstructor(update);
      observer.observe(host);
      return () => {
        observer.disconnect();
        visualViewport?.removeEventListener("resize", update);
        visualViewport?.removeEventListener("scroll", update);
      };
    }
    window.addEventListener("resize", update);
    return () => {
      window.removeEventListener("resize", update);
      visualViewport?.removeEventListener("resize", update);
      visualViewport?.removeEventListener("scroll", update);
    };
  }, [onMetricsChange]);

  const style = { "--reference-scale": String(scale) } as CSSProperties;
  return (
    <div ref={hostRef} className="reference-host" style={style}>
      <div
        {...planeProps}
        className={`reference-plane${className ? ` ${className}` : ""}`}
      >
        {children}
      </div>
    </div>
  );
}
