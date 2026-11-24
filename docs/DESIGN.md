# Design

## Source of truth

- `docs/design/tokens.json`: the "Rack" tokens (M24). The UI reads them at start through `app/ui/Tokens.kt`.
- This file: the layout rules, the screens and every deviation from the original mockup.
- `docs/design/AimTune.pdf`: the original "Nocturne" mockup (frames 1a-1n, 1920×1080). It is history now. Its content (what each screen shows) still applies where this file does not say otherwise; its look (violet accent, outlined buttons, page layout) is replaced by Rack. See ADR 0006.

All numbers in mockups and screenshots are sample data, not defaults.

## Design system "Rack" (M24)

The app looks like a rack of studio hardware: dark graphite modules with a light bevel, recessed display wells, one amber signal colour and small status LEDs.

- **Colours:** bg `#121314`, module `#1B1D1F` with bevel hi `#2A2D31` and lo `#0A0B0C`, well `#0D0E0F`, text `#ECEDEE`, divider `rgba(236,237,238,0.10)`; neutral ramp 100-900.
- **One accent:** signal amber `#FFB020` (ramp 100-900). It marks the one main action of a module, the selected item, the readout values and the primary data line. Text on an amber fill uses `onAccent` `#121314`.
- **LEDs:** ok `#3DDC97`, caution `#FF7A45`, off `#3A3E43`; live is the accent with a pulse. An LED never stands alone: the module head always shows the state as text next to it.
- **Chart colours (Okabe-Ito, colour-blind safe):** baseline `#56B4E9` (dashed `14 8`), candidate amber (solid), good `#009E73`, warning `#D55E00`, fit line `#FFD06B`, band `rgba(255,176,32,0.18)`. Series also differ by dash and label, never by hue alone.
- **Type:** Inter for interface text, Inter Display SemiBold for titles and headings, JetBrains Mono for every number, unit and timer. Sizes in design units (1080 high): label 15 (uppercase), caption 16, body 19, bodyLg 21, title 30, heading 52, display 64, numberMd 40, numberLg 96, numberXl 150. Labels and buttons use sentence case, only the small module and section labels are uppercase. Percent values in mono have no space before % (the space reads as a wide gap).
- **Spacing** 4-8-12-16-24-32-48-64, **radius** 6/10/16 (sm/md/lg), margin 64, header 88, footer 104.
- **Motion:** base 0.22 s, slow 0.42 s (`Motion.kt`): fade, pulse, count-up of readouts, module flip, button press, `Reveal` of content, chart draw-on, screen transition. Motion never carries information alone. The screenshot tour sets `Motion.instant`.

### Components

| Component | Where | Look and behaviour |
|---|---|---|
| Shell (`UiScreen.frame`, `Shell.kt`) | every UI screen | `Chrome.FULL`: top bar with the brand, the setup pill (game, sens, DPI, cm/360°; a click opens the setup panel) and the utility bar History, Crosshair, Settings (the active tool is marked). `Chrome.MINIMAL` inside runs and onboarding |
| Rack module (`RackModule`) | dashboard | Bevelled module with a head (uppercase name, LED, status text, flip button). Front: a readout or chart and one main action. Back: the advanced options; the back scrolls inside the module at the height of the front. F / G flip SENS / CURVE |
| Opened module (`modulePanel`) | results, history, curve flow, settings, crosshair, update, share | The same head over the full content area |
| Well (`Styles.WELL`) | readouts, charts, previews, info panels | Recessed panel, darker than the module |
| Readout (`Readout`) | dashboard, results, curve | Big mono value in amber with its unit; counts up when it appears |
| Primary action (`PrimaryAction`) | module fronts, curve layout | The one amber fill button of a module, with inline check chips. A click on a chip confirms it. A chip never blocks the sens start |
| Disclosure | onboarding, results | One line of text plus "More" / "Less" |
| Icon button, LED, icons (`Icons.kt`) | heads, utility bar | Procedural line icons drawn into textures; Scene2D has no SVG |
| Setup panel (`SetupPanel`) | from the setup pill and the "Change setup" links | Overlay over the current screen: game, DPI, sensitivity with live cm/360°, aim style, pad, flash; sticky Save / Cancel; Enter saves, Esc cancels |
| Round overlay (`RoundOverlayScreen`) | between rounds | One card over the dimmed (45 %) and vignetted room of the last round |
| Screen transition | between UI screens | A snapshot of the old screen fades and recedes over the new one; off in the tour and into the trainer |

## Screens and flow

```
Dashboard ─ SENS: Find my sens (Enter) ─▶ [not set up: Onboarding] ─▶ Rounds (HUD ⇄ Round overlay) ─▶ Results
          │       Range start variant (M) ─▶ Range rounds ─▶ Results
          ├ CURVE: Find a curve (A) ─▶ curve layout (intro, calibration, search, analysis, draft, result)
          │        back: Refine, Last search, Last analysis, Raw Accel settings ─▶ setting page / patched export
          ├ Setup pill ─▶ Setup panel
          └ Utility bar: History (H) ─▶ Share data · Crosshair (C) · Settings (S)
```

| Screen | Content |
|---|---|
| Dashboard (`MainMenuScreen`) | Two rack modules side by side from 1280 units wide, else stacked. **SENS** front: the last recommendation as readout (or "not set up"), the check chips (Windows pointer acceleration, Raw Accel off, input source, target sizes measured or estimated) and "Find my sens"; back: round length, scenarios, metric weights, Range options, saved at once. **CURVE** front: the imported Raw Accel profile as a chart and "Find a curve"; back: the "Runs use" block, the imported profile, Refine, Last search, Last analysis and the ten Raw Accel setting rows. Footer: "Open source · MIT", Exit, and "Version X is available (U)" when an update is found |
| Onboarding (`OnboardingScreen`) | One screen: game, DPI (field plus presets 400-3200) and in-game sensitivity left; the live cm/360° with the formula and the pad fit line right; then aim style, mousepad and flash turn-away, preset to Mixed, Medium, Sometimes. Finish is disabled while a value has an issue; Enter finishes, Esc goes to the dashboard |
| In-test HUD (`Hud`) | See "Trainer visual style" |
| Round overlay | Blind: "Round n complete", the scenario, hits or time on target, confidence bar, Next round / Stop for today. From the second round of a phase a 3 s countdown starts the next round without a click (only while the window has focus); Space or Pause pauses, Resume restarts the full 3 s, losing focus pauses. No countdown at 85 % confidence, at drift, and in refine before the feel vote. Accel and refine use the same overlay |
| Results (`ResultsScreen`) | Opened module: well with the in-game sensitivity (count-up) and cm/360° with its range, the confidence with bar and advice, the head LED and status (settled, more rounds help, drift, range edge), the score chart (fitted line, 95 % band, round dots, dashed optimum) that draws on. "Continue testing" (Enter) starts the next blind test directly |
| History (`HistoryScreen`) | Opened module: session count and game filter in the head, the latest recommendation as readout, the trend chart, a table of the newest sessions (newest row bright), "Share data" |
| Curve layout (`AccelFitScreen` and its screens) | Head CURVE with LED and status; left column 640 wide with headline, text, readout and the action; the big curve display right, drawn on at first build. The intro shows the check chips and keeps its start disabled until Raw Accel is confirmed. Refine import, stage and result, the setting page and the patched export use the same layout; the setting page and the export go back to the dashboard with CURVE on its back |
| Settings (`SettingsScreen`) | App options only: language, sounds (volume, Play, flash brightness), updates (version, auto check, check now), "Your rounds" (share data, start over with two clicks). Every change is saved at once; Reset restores sounds, levels and the update check |
| Crosshair (`CrosshairScreen`) | Profiles and the code left; the big preview in a well in the centre (background tiles above, the error note below); option groups right (color, outlines, center dot, inner lines, outer lines), one open at a time. Save keeps the working copy |
| Update, Share data | Opened modules: text left, a well right (download progress, or what the file holds). The head LED shows ready, running, failed or "release page only" (update) and the stored counts (share). Share data goes back to the screen that opened it |
| Contribute training data (`ContributeScreen`, off by default, ADR 0007) | Opened module from Share data: why it helps, voluntary, the three steps and the state left; the well right lists what the file contains, what it does not, and what sharing on GitHub means. Footer: Create file, then Show file and Open issue on GitHub (Copy link after a browser failure). The head LED shows the counts, ready or failed |

Keyboard: Enter starts the main action of the screen, Esc goes back (closes the setup panel first), A opens the curve, F / G flip the modules, M switches the Range variant, R results, H history, C crosshair, S settings, U update. Tab navigation stays in all forms.

## Trainer visual style

The 3D scene is a test condition, so M24 left it as it was: a dark minimal map with a floor at eye height, a ring wall, pillars at corners, door frames, head-sized targets on columns (fill `#b5abfc`, darker ring) and the player's own Valorant crosshair in screen pixels (`Theme.kt` holds the scene colours).

The HUD (`Hud.kt`) uses the Rack type and colours. It sits in the margins, away from the centre:

- top left: the scenario name as an uppercase label in amber;
- top centre: the timer in mono (numberMd);
- top right: "Round n / N" in the blind test (never the sensitivity), else "Hits / Misses" or "Kills / Shots" in Range mode;
- bottom left: "Esc aborts the round" as a muted caption.

The blind ready text shows "ROUND N / M" as an amber label over the click prompt. The round summary of free play shows "ROUND COMPLETE" as a label and the analysis lines in mono. The debug overlay exists only in free play.

### Range mode (M15c)

Original geometry, no assets of any game. The bot is a head sphere in sand (`#e6d9b8`) on an ochre torso and pelvis (`#c79a55`) with darker limbs (`#8f7048`), boxes only, turned to face the player. Its proportions are those of the hit model (`BotShape`: 1.8 m, head 0.25 m, body capsule radius 0.2 m), so what is drawn is what is hit. A low counter (1.6 × 0.4 m, 0.8 m high) stands in front of the player; lit lines on the floor mark a lane every 10 degrees of bearing from -50 to +80 degrees and arcs at 5, 12.5 and 20 m. Corners and doorways are left out in Range mode.

## Accessibility

- Colour-blind safe chart palette; series also differ by dash style and label. Status always has text next to the LED or icon.
- Interface text at least 15 design units (labels), body 19.
- Full keyboard navigation; every module action has a key.
- Motion is short (at most 0.42 s) and never the only carrier of information.

## i18n

LibGDX `I18NBundle`: `assets/i18n/messages.properties` (English default) and `messages_de.properties`, the same keys in both (checked by a test). No user-visible strings in code. Layouts must fit German text (about +30 %); the screenshot tour checks EN and DE at 1920×1080 and 1280×720. Numbers use the locale decimal separator for display only; stored values are locale-independent.

## Scene2D mapping

- The UI stage has a design height of 1080 units; the width follows the window aspect. From 1280 units wide the dashboard and the onboarding have two columns (`HubLayout`), else one. A width change that crosses 1280 rebuilds the screen.
- `UiSkin` builds the skin in code from `Tokens`: bevels, wells, glows, LEDs and icon textures come from `Drawables.kt` and `LayeredDrawable`; there is no skin JSON or atlas file.
- Fonts: Inter 4.1 (Regular, Medium), Inter Display SemiBold and JetBrains Mono 2.304 (Regular), all SIL OFL 1.1, bundled in `app/src/main/resources/assets/fonts/` with their licenses. gdx-freetype rasterizes them at the exact pixel size of the framebuffer (`Fonts.kt`) and rebuilds them when the window height changes by more than 8 %.
- Charts are custom actors drawn with `ShapeRenderer` (`ChartActor`: lines, band, dashes, dots, draw-on).
- The round overlay dims an FBO copy of the last round (`RoundBackdrop`).

## Deviations

Deviations from the original mockup (`AimTune.pdf`) that still apply:

| Mockup | Implementation | Reason |
|---|---|---|
| Nocturne look: violet accent, outlined buttons, page per screen | Rack: graphite modules, amber accent, one filled main action per module, dashboard with two modules (ADR 0006) | Fewer screens and one clear action per task |
| Main menu list (Start, Continue, Results, History, Settings) | Dashboard with SENS and CURVE modules and a utility bar; no "Continue session" | A new run merges the stored rounds, so there is nothing to continue |
| Onboarding in four steps (1c-1f) | One screen with live cm/360° | All values are visible at once; Finish shows what is missing |
| Pre-test notice (1g) | Check chips on the SENS front and on the curve intro; a click confirms the Raw Accel chip | The checks belong next to the start; the sens start is never blocked |
| Raw Accel row with "Open Raw Accel" | The player confirms "Raw Accel is off or linear" | Raw Accel has no query API; the Windows pointer acceleration is detected |
| Check chips never block a start | The curve start stays disabled until the Raw Accel chip is confirmed | The blind curve runs apply the curve in the app; a curve in the driver on top would falsify every round |
| Settings (1n) holds the setup, the weights and the language | Settings holds app options only and saves at once; the setup is in the setup panel; round length, scenarios, weights and Range options are on the SENS back | One place per setting |
| In-test HUD (1h) with a progress bar and "Esc · pause" | Timer, scenario, "Round n / N", "Esc aborts the round"; no bar, no pause | Aborting discards the round; a pause would taint it |
| Between rounds (1i): "Good round.", 85 % marker | Neutral "Round n complete", plain bar, target in the text; countdown from round 2 | No judgement of a single round; fewer clicks |
| Results (1j): chart right of the numbers, tabs, legend | Numbers in a well on top, the chart below, no tabs, no legend (the caption names the elements) | The chart needs the full width; the curve has its own flow |
| Acceleration as a results tab (1k, 1l) | The CURVE module and the curve layout | The curve needs its own rounds and a decision before an export |
| History (1m) with a full table | The newest sessions with a note for the rest | No scrolling inside the opened module |
| Crosshair is a small neutral cross | The player's own Valorant crosshair; Crosshair screen with presets and the game's ranges | Players aim differently with an unfamiliar crosshair (M12) |
| 2D circles as targets | 3D spheres with a ring | The trainer uses the game's FOV |
| Label letter spacing +10 % | Dropped | `BitmapFont` has no tracking |
| Greek letters in formulas | Words ("angle") and × ÷ ° · | The font atlas has no theta or pi |
| Game tiles for five games | Only games with verified yaw and FOV | Never guess values |

## Open design questions

See `docs/PROGRESS.md` ("Open questions"). The round countdown has no setting to turn it off: after a hands-on test the user keeps it as it is (2026-11-24).
