import { useEffect, useRef, type CSSProperties } from "react";
import { CreateScreen } from "./CreateScreen";
import { DashboardScreen } from "./DashboardScreen";
import { EventHistoryScreen } from "./EventHistoryScreen";
import { RetainedDashboardStack, type DashboardOverlayRoute } from "./RetainedDashboardStack";
import { RouteTransition } from "./RouteTransition";
import { ScheduledStoryScreen } from "./ScheduledStoryScreen";
import type { AppRoute, AppSnapshot } from "./contracts";
import { motionCssVariables, projectImeMotion } from "./motionConfig";
import { appRootTransitionSpec, type TransitionRoute } from "./routeTransitionModel";
import { useAppController } from "./useAppController";

function isDashboardStackRoute(
  route: AppRoute,
): route is "dashboard" | DashboardOverlayRoute {
  return route === "dashboard" || route === "events" || route === "story";
}

export function StartupError({ message, retry }: { message: string; retry: () => void }) {
  return (
    <main className="startup-state">
      <p role="alert">{message}</p>
      <button type="button" onClick={retry}>
        Повторить
      </button>
    </main>
  );
}

export function StartupLoading() {
  return (
    <main className="startup-state" aria-label="Загрузка" aria-busy="true">
      <p className="sr-only" role="status" aria-live="polite">
        Загрузка приложения
      </p>
    </main>
  );
}

export function startupErrorMessage(error: string | null): string {
  if (error === "UNSUPPORTED_PROTOCOL") {
    return "Версия приложения несовместима с интерфейсом. Обновите приложение.";
  }
  if (error === "NATIVE_BRIDGE_UNAVAILABLE") {
    return "Android System WebView недоступен. Обновите системный компонент и приложение.";
  }
  return "Не удалось открыть приложение.";
}

export function App() {
  const controller = useAppController();
  const snapshot = controller.snapshot;
  const lastCreateSnapshotRef = useRef<AppSnapshot | null>(null);
  const lastDashboardSnapshotRef = useRef<AppSnapshot | null>(null);

  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    let frame: number | null = null;
    const publishOffset = () => {
      if (frame !== null) return;
      frame = window.requestAnimationFrame(() => {
        frame = null;
        const offsetTop = Number.isFinite(viewport.offsetTop)
          ? Math.max(0, viewport.offsetTop)
          : 0;
        document.documentElement.style.setProperty(
          "--visual-viewport-offset-top",
          `${offsetTop}px`,
        );
      });
    };
    publishOffset();
    viewport.addEventListener("resize", publishOffset);
    viewport.addEventListener("scroll", publishOffset);
    return () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
      viewport.removeEventListener("resize", publishOffset);
      viewport.removeEventListener("scroll", publishOffset);
      document.documentElement.style.setProperty("--visual-viewport-offset-top", "0px");
    };
  }, []);

  if (snapshot?.route === "create" && snapshot.create) {
    lastCreateSnapshotRef.current = snapshot;
  } else if (snapshot?.pet && isDashboardStackRoute(snapshot.route)) {
    lastDashboardSnapshotRef.current = snapshot;
  }

  useEffect(() => {
    if (snapshot?.route === "events") {
      controller.markEventsViewed(snapshot.events?.latestEventAtEpochMillis ?? null);
    }
  }, [controller.markEventsViewed, snapshot?.events?.latestEventAtEpochMillis, snapshot?.route]);

  if (controller.loading && !snapshot) {
    return <StartupLoading />;
  }
  if (!snapshot) {
    return (
      <StartupError
        message={startupErrorMessage(controller.error)}
        retry={() => void controller.retryBootstrap()}
      />
    );
  }

  const imeMotion = projectImeMotion(snapshot.safeArea.imeProgress);
  const safeAreaStyle = {
    ...motionCssVariables,
    "--safe-top": `${snapshot.safeArea.top}px`,
    "--safe-right": `${snapshot.safeArea.right}px`,
    "--safe-bottom": `${snapshot.safeArea.bottom}px`,
    "--safe-left": `${snapshot.safeArea.left}px`,
    "--ime-height": `${snapshot.safeArea.imeHeight}px`,
    "--ime-progress": imeMotion.progress,
    "--ime-media-shift": `${imeMotion.mediaShiftPixels}px`,
    "--ime-dialogue-shift": `${imeMotion.dialogueShiftPixels}px`,
  } as CSSProperties;

  if (snapshot.route === "connectionError") {
    return (
      <StartupError
        message="Не удалось подключиться к серверу. Проверьте интернет."
        retry={() => void controller.retryBootstrap()}
      />
    );
  }
  if (snapshot.route === "localDataError") {
    return (
      <StartupError
        message="Не удалось открыть данные питомца."
        retry={() => void controller.retryBootstrap()}
      />
    );
  }

  const rootRoute = snapshot.route === "create" ? "create" : "dashboard";
  const renderRootRoute = (route: TransitionRoute) => {
    const routeSnapshot = route === "create"
      ? lastCreateSnapshotRef.current
      : route === "dashboard"
        ? lastDashboardSnapshotRef.current
        : null;

    if (route === "create" && routeSnapshot?.create) {
      return (
        <CreateScreen
          state={routeSnapshot.create}
          busy={controller.loading}
          reducedMotion={routeSnapshot.reducedMotion}
          customOpen={controller.createCustomOpen}
          customValue={controller.createCustomValue}
          onOpenCustom={controller.openCreateCustom}
          onCloseCustom={controller.closeCreateCustom}
          onUpdateCustom={controller.updateCreateCustom}
          feedback={controller.feedback}
          dispatch={controller.dispatch}
        />
      );
    }

    if (
      route === "dashboard" &&
      routeSnapshot?.pet &&
      isDashboardStackRoute(routeSnapshot.route)
    ) {
      return (
        <RetainedDashboardStack
          route={routeSnapshot.route}
          reducedMotion={routeSnapshot.reducedMotion}
          renderDashboard={(active) => (
            <DashboardScreen
              pet={routeSnapshot.pet!}
              mode={controller.dashboardMode}
              busy={controller.loading}
              petTapFeedback={controller.petTapFeedback}
              reducedMotion={routeSnapshot.reducedMotion}
              lifecycleState={controller.lifecycleState}
              active={active && rootRoute === "dashboard"}
              eventsBadgeCount={routeSnapshot.events?.badgeCount ?? 0}
              firstSession={routeSnapshot.firstSession}
              dashboard={routeSnapshot.dashboard ?? null}
              onOpenMode={controller.openDashboardMode}
              onCloseMode={controller.closeDashboardMode}
              feedback={controller.feedback}
              dispatch={controller.dispatch}
              onDraftChange={controller.updateDashboardDraft}
              flushDraft={controller.flushDashboardDraft}
            />
          )}
          renderOverlay={(overlayRoute) => {
            if (overlayRoute === "events" && routeSnapshot.events) {
              return (
                <EventHistoryScreen
                  stories={routeSnapshot.events.stories}
                  travelVideos={routeSnapshot.events.travelVideos}
                  reducedMotion={routeSnapshot.reducedMotion}
                  foreground={controller.lifecycleState === "foreground"}
                  initialFocusTravelRequestKey={
                    routeSnapshot.events.initialFocusTravelRequestKey
                  }
                  onShare={(asset) => controller.shareTravelVideo(asset.requestKey)}
                  onHelp={(item) => {
                    void controller.dispatch("STORY_OPEN", { storyId: item.story.storyId });
                  }}
                  onBack={() => {
                    controller.feedback("buttonPress");
                    void controller.dispatch("BACK");
                  }}
                />
              );
            }
            if (overlayRoute === "story" && routeSnapshot.story) {
              return (
                <ScheduledStoryScreen
                  state={routeSnapshot.story}
                  reducedMotion={routeSnapshot.reducedMotion}
                  foreground={controller.lifecycleState === "foreground"}
                  choicePending={
                    routeSnapshot.story.phase === "choicePending" ||
                    controller.storyChoicePending?.storyId === routeSnapshot.story.story.storyId
                  }
                  onChoice={(choice) => {
                    void controller.chooseStory(routeSnapshot.story!.story.storyId, choice);
                  }}
                  onRetry={() => {
                    void controller.dispatch("STORY_RETRY", {
                      storyId: routeSnapshot.story!.story.storyId,
                    });
                  }}
                  onFinish={() => {
                    void controller.dispatch("STORY_FINISH", {
                      storyId: routeSnapshot.story!.story.storyId,
                    });
                  }}
                  onBack={() => {
                    controller.feedback("buttonPress");
                    void controller.dispatch("BACK");
                  }}
                />
              );
            }
            return null;
          }}
        />
      );
    }
    return null;
  };

  return (
    <div
      className={snapshot.reducedMotion ? "app app--reduced-motion" : "app"}
      style={safeAreaStyle}
      data-route={snapshot.route}
    >
      <RouteTransition
        route={rootRoute}
        reducedMotion={snapshot.reducedMotion}
        transitionSpec={appRootTransitionSpec}
        focusInitial={false}
        label="Переход между созданием и питомцем"
        isRoutePresent={(route) => (
          (route === "create" && Boolean(lastCreateSnapshotRef.current?.create)) ||
          (route === "dashboard" && Boolean(lastDashboardSnapshotRef.current?.pet))
        )}
        renderRoute={renderRootRoute}
      />
    </div>
  );
}
