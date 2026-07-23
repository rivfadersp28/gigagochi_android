import {
  useEffect,
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type AnimationEvent,
  type CSSProperties,
  type ReactNode,
} from "react";
import { motionConfig } from "./motionConfig";
import {
  routeTransitionAnimationClass,
  routeTransitionSpec as defaultRouteTransitionSpec,
  transitionRouteLabel,
  type RouteTransitionSpec,
  type TransitionRoute,
} from "./routeTransitionModel";
import "./routeTransition.css";

interface RouteLayer {
  id: number;
  route: TransitionRoute;
}

interface RouteTransitionState {
  targetRoute: TransitionRoute;
  incoming: RouteLayer;
  outgoing: RouteLayer | null;
  spec: RouteTransitionSpec | null;
  sequence: number;
}

export interface RouteTransitionProps {
  route: TransitionRoute;
  reducedMotion: boolean;
  renderRoute(route: TransitionRoute): ReactNode;
  isRoutePresent?(route: TransitionRoute): boolean;
  onTransitionSettled?(route: TransitionRoute, sequence: number): void;
  transitionSpec?(
    initial: TransitionRoute,
    target: TransitionRoute,
    reducedMotion: boolean,
  ): RouteTransitionSpec;
  focusInitial?: boolean;
  className?: string;
  label?: string;
}

function initialState(route: TransitionRoute): RouteTransitionState {
  return {
    targetRoute: route,
    incoming: { id: 0, route },
    outgoing: null,
    spec: null,
    sequence: 0,
  };
}

function sameRoute(left: TransitionRoute, right: TransitionRoute): boolean {
  return left === right;
}

export function RouteTransition({
  route,
  reducedMotion,
  renderRoute,
  isRoutePresent = () => true,
  onTransitionSettled,
  transitionSpec = defaultRouteTransitionSpec,
  focusInitial = true,
  className,
  label = "Переход между экранами",
}: RouteTransitionProps) {
  const [state, setState] = useState<RouteTransitionState>(() => initialState(route));
  const lastSettledNotification = useRef(0);
  const incomingLayerRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    setState((current) => {
      if (sameRoute(current.targetRoute, route)) {
        if (reducedMotion && current.outgoing) {
          return { ...current, outgoing: null, spec: null };
        }
        return current;
      }

      const sequence = current.sequence + 1;
      const spec = transitionSpec(current.targetRoute, route, reducedMotion);
      const incoming = { id: sequence, route };
      if (spec.kind === "instant") {
        return {
          targetRoute: route,
          incoming,
          outgoing: null,
          spec: null,
          sequence,
        };
      }
      return {
        targetRoute: route,
        incoming,
        // On interruption the previous target becomes the only outgoing layer. Any older exit is
        // discarded immediately, matching AnimatedContent's replace semantics without stale DOM.
        outgoing: current.incoming,
        spec,
        sequence,
      };
    });
  }, [reducedMotion, route, transitionSpec]);

  const settle = useCallback((sequence: number) => {
    setState((current) => (
      current.sequence === sequence && current.outgoing
        ? { ...current, outgoing: null, spec: null }
        : current
    ));
  }, []);

  useEffect(() => {
    if (!state.outgoing || !state.spec) return;
    const sequence = state.sequence;
    const timeout = window.setTimeout(
      () => settle(sequence),
      state.spec.totalDurationMillis,
    );
    return () => window.clearTimeout(timeout);
  }, [settle, state.outgoing, state.sequence, state.spec]);

  useEffect(() => {
    if (
      state.outgoing ||
      state.sequence === 0 ||
      lastSettledNotification.current >= state.sequence
    ) return;
    lastSettledNotification.current = state.sequence;
    onTransitionSettled?.(state.targetRoute, state.sequence);
  }, [onTransitionSettled, state.outgoing, state.sequence, state.targetRoute]);

  const incomingPresent = (
    sameRoute(state.targetRoute, route) &&
    isRoutePresent(state.incoming.route)
  );
  useLayoutEffect(() => {
    if (!incomingPresent || (!focusInitial && state.sequence === 0)) return;
    const focusTarget = incomingLayerRef.current?.querySelector<HTMLElement>("main");
    if (!focusTarget) return;
    // <main> is not in sequential focus order. A -1 programmatic target prevents focus from
    // remaining inside the outgoing inert layer without adding a keyboard tab stop.
    if (!focusTarget.hasAttribute("tabindex")) focusTarget.tabIndex = -1;
    focusTarget.focus({ preventScroll: true });
  }, [focusInitial, incomingPresent, state.incoming.id, state.sequence]);

  const layer = (
    item: RouteLayer,
    role: "enter" | "exit",
  ) => {
    const animating = state.outgoing !== null && state.spec !== null;
    const incoming = role === "enter";
    const routePresent = isRoutePresent(item.route);
    const interactive = incoming && routePresent;
    const animationClass = animating && state.spec
      ? routeTransitionAnimationClass(state.spec, role)
      : null;
    const finishIncomingAnimation = (event: AnimationEvent<HTMLDivElement>) => {
      if (event.currentTarget !== event.target || !incoming) return;
      settle(state.sequence);
    };
    return (
      <div
        key={item.id}
        ref={incoming ? incomingLayerRef : undefined}
        className={[
          "route-transition__layer",
          animating ? "route-transition__layer--animating" : "",
          animating && state.spec
            ? `route-transition__layer--animating-${state.spec.kind}`
            : "",
          interactive ? "route-transition__layer--interactive" : "",
          animationClass ?? "",
        ].filter(Boolean).join(" ")}
        data-route={transitionRouteLabel(item.route)}
        data-transition-role={incoming ? "incoming" : "outgoing"}
        data-transition-sequence={state.sequence}
        aria-hidden={interactive ? undefined : true}
        inert={interactive ? undefined : true}
        onAnimationEnd={finishIncomingAnimation}
      >
        {renderRoute(item.route)}
      </div>
    );
  };

  return (
    <div
      className={["route-transition", className].filter(Boolean).join(" ")}
      style={{
        "--route-fade-in-duration": `${
          state.spec?.kind === "fade"
            ? state.spec.enter.durationMillis
            : motionConfig.route.fadeInDurationMillis
        }ms`,
        "--route-fade-out-duration": `${
          state.spec?.kind === "fade"
            ? state.spec.exit.durationMillis
            : motionConfig.route.fadeOutDurationMillis
        }ms`,
        "--route-slide-duration": `${
          state.spec?.kind === "slide"
            ? state.spec.enter.durationMillis
            : motionConfig.route.slideDurationMillis
        }ms`,
        "--route-fade-easing": state.spec?.kind === "fade"
          ? state.spec.enter.easing
          : motionConfig.easing.fastOutSlowIn.css,
        "--route-slide-easing": state.spec?.kind === "slide"
          ? state.spec.enter.easing
          : motionConfig.easing.routeTransition.css,
        "--route-full-offset": `${motionConfig.route.fullOffsetPercent}%`,
        "--route-negative-parallax-offset":
          `${-motionConfig.route.parallaxOffsetPercent}%`,
      } as CSSProperties}
      role="group"
      aria-label={label}
      data-transition-active={state.outgoing ? "true" : "false"}
      data-transition-sequence={state.sequence}
    >
      {state.outgoing ? layer(state.outgoing, "exit") : null}
      {layer(state.incoming, "enter")}
    </div>
  );
}
