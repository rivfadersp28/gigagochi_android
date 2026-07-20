# Gigagochi Android Interface System

## Direction and feel

- The interface is a tactile, playful companion world: cloud dioramas, living pet media, soft glass controls, and restrained white foreground chrome.
- Navigation should feel embedded in the scene rather than placed on an opaque toolbar.
- Preserve the existing scene palette: cloud white, mist blue, deep forest/navy shadows, warm sun, and translucent white glass.
- OpenRunde remains the product typeface for chrome and controls. Do not introduce a separate navigation typeface.

## Depth strategy

- Use one glass depth language for scene controls: Haze blur plus a translucent tint and clipped inner highlights/shades.
- Contextual navigation and dashboard actions share `GlassActionSurfaceContract`:
  - radius: `24.dp`
  - blur: `12.dp`
  - white tint: `15%`
  - noise: `0`
  - highlight inset: white `20%`, radius `3.dp`, offset `(1.dp, 2.dp)`
  - shade inset: black `20%`, radius `2.dp`, offset `(-4.dp, -4.dp)`
- Use the contract fallback tint when Haze is unavailable. Do not replace glass with an unrelated opaque circle.

## Spacing

- Base spacing unit: `4.dp`.
- Contextual navigation touch target: `48.dp`.
- App bar edge padding: `16.dp` after safe drawing insets.
- Gap from contextual app bar to the first content block: `18.dp`.
- Keep navigation outside fixed `402 x 874 dp` reference frames so system safe areas are applied in viewport coordinates.

## Contextual navigation pattern

- Use `ContextualGlassNavigation` with `ContextualNavigationAction.Back`; do not create screen-local arrow buttons.
- Pass the screen's `HazeState` so the circular control blurs the live scene beneath it.
- The glyph is the shared white, auto-mirrored `24.dp` back arrow.
- Apply `WindowInsets.safeDrawing` for top and horizontal insets, then `ContextualAppBarEdgePadding`.
- Pair the visible action with Android `BackHandler` and route both through the same state transition.
- Back from a nested input closes only that input, returns to the same parent stage, and clears its transient draft unless the feature contract explicitly says drafts are durable.
- The pattern applies to non-idle Dashboard modes, Events, interactive stories, Travel, and Create custom input.

## Creation-stage motion

- Creation choices use the shared tilted Haze buttons and their existing stagger.
- Entrance identity must include the creation stage, not only the label. Repeated labels such as `Свой вариант` must disappear and replay the same entrance as sibling options after a stage change.
- Reduced motion renders controls immediately without entrance animation.

## Avoid

- Opaque top bars over full-scene media.
- Screen-specific back glyphs, sizes, or blur values.
- Navigation inside the scaled/cropped reference canvas.
- Dark navigation glyphs on the cloud scene.
- A visible back button whose behavior differs from the Android system Back action.
