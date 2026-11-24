# 0006: UI "Rack"

## Context
The first UI followed the Nocturne mockup (`docs/design/AimTune.pdf`): a violet accent, outlined buttons and one page per step. With the Raw Accel hub (M22f), Range mode and the curve tools, the menu had seven items and a start took up to six screens (menu, onboarding in four steps, pre-test notice). The setup was edited in Settings, so every flow screen needed a settings return target. Users did not see the one action that a screen was for.

## Decision
M24 replaces the look and the navigation with "Rack": dark graphite modules like studio hardware, one amber accent (`#FFB020`), recessed display wells and status LEDs with a text next to them. Tokens live in `docs/design/tokens.json`; `UiSkin` builds every drawable in code.

- The home screen is a dashboard with two rack modules: SENS (find the sensitivity) and CURVE (find or refine a Raw Accel curve). Each module front has one main action; the advanced options are on its back.
- The pre-test notice becomes check chips next to the start. A click confirms a chip. The sens start is never blocked; the curve start waits for the Raw Accel chip, because a driver curve would falsify the blind curve runs.
- The onboarding is one screen. The setup is edited in a setup panel that opens over any screen; Settings holds app options only and saves at once.
- Between rounds is an overlay over the dimmed last round, with a 3 s countdown from the second round.
- Results, history, the curve flow, crosshair, update and share data open as full-size modules with the same head (name, LED, status).
- The 3D trainer scene keeps its look, because it is a test condition. The HUD only takes the Rack type and colours.

## Consequences
- A blind test starts in one click from the dashboard; "Continue testing" on the results starts the next test directly.
- `PreTestScreen`, `RawAccelHubScreen`, `SoundSettingsScreen` and `SettingsExit` are gone; `PreTestChecks` stays as the model of the check chips.
- Motion (`Motion.kt`) adds count-up, flip, draw-on and screen transitions. The screenshot tour turns it off with `Motion.instant`, so shots stay comparable.
- The PDF mockup is history. DESIGN.md lists the deviations that still apply.
- Not tested by a person yet: the countdown with a real mouse, and the dashboard at window sizes other than 1920×1080 and 1280×720.
