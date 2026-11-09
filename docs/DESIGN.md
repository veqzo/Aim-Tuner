# Design

## Source of truth

- `docs/design/AimTune.pdf`: one tall page ("Design system and 12 screens (1920×1080). Built on Nocturne, adds a monospace numeric face and Okabe-Ito chart colors"). 14 frames in 2 columns × 7 rows, labelled 1a-1n.
- `docs/design/tokens.json`: the Nocturne tokens used below.

The UI follows both. Tokens are translated into a Scene2D skin in `assets/ui/`. Mockups are web designs; where Scene2D cannot reproduce something 1:1, the deviation is listed under "Deviations". All numbers in the mockups (43.3, 1.24, 41.9 …) are sample data, not defaults.

Re-rendering the PDF: `pdftoppm -r 150 -png docs/design/AimTune.pdf out`, then crop the 2×7 grid (frames are 1920×1080 scaled).

## Design system (frame 1a)

- **One accent** (`#9184d9`), used for outlines, selection and the primary data line. Buttons are outlined, no fills. Selected items (tiles, segmented options, list rows) get a dark accent tint (accent ramp 800) with an accent outline.
- Surfaces: bg `#161826`, surface `#232532` (cards, inputs, list rows), divider `rgba(233,233,237,0.16)`, text `#e9e9ed`; neutral and accent ramps 100-900.
- **Chart colors (Okabe-Ito, colorblind-safe):** A/baseline `#56B4E9`, B/candidate `#E69F00`, good `#009E73`, warning `#D55E00`, fit line `#b5abfc`, band `rgba(145,132,217,0.22)`. Series also differ by line style (baseline dashed `14 8`, candidate solid) and by label, never by hue alone.
- **Type:** Inter for all interface text, JetBrains Mono for all numbers, units and timers. Sizes at 1920×1080: display 64 / heading 56 (screen titles), title 32/500 (section titles), bodyLg 22, body 20/400 (minimum 16 px for interface text), label 14/500 uppercase with +10% tracking, mono numbers 44 / 96 / 150 (numberMd/Lg/Xl).
- **Spacing** 4-8-12-16-24-32-48-72, **radius** 4/8/14 (sm/md/lg), margin 72, header 88, footer 120.
- **Components:** primary button (outlined accent, accent text), secondary button (outlined neutral, light text), text button (accent text, e.g. Back), chips (Recommended = tinted fill, Session 14 = neutral fill, Blind test = outlined), segmented control (Wrist/Mixed/Arm), numeric input (mono value with a small unit suffix such as "DPI"), cards on surface color, list rows with a right-aligned mono value or key hint, slider with value label, progress bar.

## Screens and flow

```
Main menu ─ Start test ─▶ [first launch: Onboarding 1-4] ─▶ Pre-test notice ─▶ Rounds (HUD ⇄ Between rounds) ─▶ Results (Sensitivity | Acceleration)
          ├ Continue session ─▶ Rounds
          ├ Results ─▶ Results
          ├ History
          └ Settings
```

| Frame | Screen | Content |
|---|---|---|
| 1b | Main menu | Title "Find the sens you aim best with."; list: Start test (Enter, "new blind session · about 20 min"), Continue session (C, "session 14 · round 6 / 10 · 70 %"), Results (R), History (H), Settings (S). Right card "Last recommendation": cm/360° in numberXl mono, game sens, confidence, rounds. Profile summary line, footer "Open source · MIT", "Raw Accel export supported", version label. |
| 1c | Onboarding 1/4 DPI | Numeric input with presets 400/800/1600/3200, "Not sure?" info card (mouse vendor apps, "DPI"/"CPI"/"Sensitivity stages"). |
| 1d | Onboarding 2/4 game + sens | Game tiles (CS2, Valorant, Apex Legends, Overwatch 2, Fortnite, "Custom yaw…"), in-game sensitivity input with "Yaw · DPI (from step 1)" hint; live **current cm/360°** in numberXl with the formula `360 × 2.54 ÷ (DPI × sens × yaw)`. |
| 1e | Onboarding 3/4 aim style | Three cards: Wrist (typ. 20-35 cm/360°), Mixed (30-50), Arm (45-80), each with icon and one-line description; selected card has check mark. |
| 1f | Onboarding 4/4 mousepad | List Small ≈27×21, Medium ≈32×27, Large ≈45×40, Desk mat ≈90×40, Custom (width × height); "Fit check" panel draws the 360° distance against the pad width and says whether the current setting fits. |
| 1g | Pre-test notice | Status rows with state label: Windows "Enhance pointer precision" (OFF/ON), Raw Accel curve (ACTIVE → warning row with "Open Raw Accel"), Mouse input in AimTune (RAW). Buttons Back, Re-check, Start test (never blocked). |
| 1h | In-test HUD (blind) | Scenario name top-left, timer in mono with thin progress bar top-center, "Round 4 / 10" top-right, crosshair, head-sized targets (accent fill with darker ring), horizon line, "Esc · pause" bottom-left. **No sensitivity is shown.** |
| 1i | Between rounds | "Round 4 / 10 complete", headline ("Good round."), three stat cards (hits / accuracy / avg time to hit), note that sensitivity stays hidden until the test ends, confidence in numberXl with bar and an "85 % recommended" marker, "About 3 more rounds to reach 85 %.", buttons Next round / Stop for today. |
| 1j | Results · Sensitivity | Recommended game sens (numberXl) "in-game sensitivity at 800 DPI", cm/360° with credible range ("Range 38.4-46.1 · now 43.3 (-3 %)"), confidence card ("46 rounds · 5 sessions"), buttons "Copy 1.24 to clipboard" and "Continue testing". Chart: score (0-100) vs cm/360° with fitted line, 95% uncertainty band, round-result dots, dashed vertical line at the optimum with value label, legend. Tabs Sensitivity / Acceleration. |
| 1k | Results · Acceleration (curve) | Chart of sensitivity multiplier (0.8×-1.6×) vs hand speed (counts/ms), A dashed blue vs B solid orange. Chip "Curve B recommended · confidence 74 %". A/B score bars ("20 rounds each", B +2.7), difference 95% CI, Raw Accel parameter list (mode, input offset, acceleration, cap), "Export Raw Accel settings". |
| 1l | Results · Acceleration (none) | Same chart; chip "No clear difference", heading "No acceleration recommended", explanation, A/B bars with CI that includes 0, "Run more A/B rounds", export link disabled ("nothing to export"). |
| 1m | History | Line chart of recommended cm/360° per session, table (date, game, rounds, cm/360°, confidence, type = Sensitivity / Accel A/B), game filter (All / per game). |
| 1n | Settings | Language (English / Deutsch), game profile dropdown with yaw hint, mouse DPI, round length 30/45/60/90 s, **metric weights** sliders (accuracy 40, time to hit 30, tracking 20, consistency 10; total shown), Save / Reset to defaults. Deviation (M23g4): in a game with duel rules (Valorant) the accuracy and time-to-hit sliders no longer change the score of click rounds (they score duel wins); only tracking still uses the weights. The weights caption then says so, and with a game scenario mix the scenarios caption says the game sets how often each scenario comes. M23d adds a row "Your rounds" with "Start over" (secondary button, two clicks: the first click arms it and the label says what will happen; the outcome is shown as the button label, not in the full footer). |

Keyboard: menu hotkeys as shown, Enter to confirm, Esc to pause/back, Tab navigation in all forms.

### Refine my Raw Accel curve (M18)

New screens, all in the existing style (cards, mono for parameters, the shared chart):

- **Import** (`refine-import`): left column with the intro, the primary button "Open settings.json..." (native file dialog, M22d), "Paste from clipboard", up to four file buttons from the import folder and its path, profile buttons, and the Raw Accel check box; right card "What was read" with the parameters in mono, the total factor and the multiplier chart (curve times output DPI / device DPI against hand speed, line at 1). A profile Raw Accel itself would refuse (or a DPI that differs from the settings) gets a caution card with the reasons, and a profile with nothing to refine says so; "Start refining" stays disabled until a profile can be refined and the check is ticked.
- **Between rounds**: the usual progress bar, with "Skip warm-up" and "End this part now" as secondary buttons; never a value or an arm.
- **Stage result** (`refine-stage`): heading "<stage>: result" (High-speed gain or Transition), one card with the best change as a signed percent, its range, confidence and rounds, and one sentence saying whether it is carried on; footer "Skip the rest, go to the test" and "Next: ...".
- **Result**: verdict, and for "better" a card listing the changed fields as `field: old -> new` with the export buttons (Copy, "Save as..." with a native save dialog, export folder); after saving the card says where the file is.

### Raw Accel hub (M22f)

The main menu item "Raw Accel" (key A, formerly "Acceleration") opens a hub; everything about Raw Accel hangs off it. Same cards, rows and type as the rest; no new tokens.

- **Layout:** the stage is 1080 units high, so its width is 1920 at 16:9 and also at a 1280×720 window. From 1280 units wide the hub has two columns of at most 820 units (gap 56); below that one column. The frame scrolls when the content is taller than the window. A width change that crosses 1280 rebuilds the screen.
- **Left column:** heading "Raw Accel"; the **"Runs use" card** (game, in-game sensitivity, cm/360°, DPI in mono, a line saying where they come from, link "Change in Settings" on the right); the imported profile line ("Imported profile: name from file" or "No profile imported"); section **Curve** with four list rows: Find a curve, Refine my curve (its line carries the honest note: it only changes the curve after a clear blind win and rarely did in testing), Last search, Last analysis.
- **Right column:** section **Other settings**, ten list rows (title, one muted state line, a badge Analysis or Info on the right): rotation, Y/X, L/R (analysis); U/D (info, the measured number only), angle snapping, input speed cap, smoothing, domain and range stretch, whole or by component and Lp norm, polling rate and time (info). Rows are 64 high with a 4 gap, so the ten rows fit at 1080. While the analyses are computed in the background the state lines say "Computing…".
- **Footer:** Back, and "Export patched copy" (disabled until a profile is imported and there is a refine result or a ticked recommendation).
- **Setting page** (`RawAccelSettingScreen`, one screen for all ten): heading with the badge; cards "What it does" (words, then each formula in mono on its own line), "Your value now" (from the imported profile), "Recommended" (value and why), "Result" (analysis rows: value, range, number of reaches, or "Not enough data: n of m reaches") or "Measured on your rounds" (info rows with numbers: snap replay rows, speed percentiles, report interval), and for info rows "Why AimTune does not measure it". Two columns put the description on the left and the recommendation and result on the right; one column reads top to bottom. A recommendation of rotation, Y/X or L/R gets the check box "Add to my patched copy (not blind-tested)", off by default and kept for the session.
- **Export page** (`RawAccelExportScreen`): the name of the added profile, every changed field `field: old -> new`, passive ones marked "(not blind-tested)", then Copy settings.json, Save as... and Save to the export folder, as on the refine result.
- **Update screen** (`UpdateScreen`, from the "Version X is available (U)" button of the main menu): the two versions, what will happen for this kind of install, status line (asking, MB downloaded, closing, or why it failed) and the buttons Back or Cancel, Open release page and Update now. Settings footer: the switch reads "Auto update check: On/Off" and "Check for updates now" shows its result as its own label, never as a footer message (a longer footer would be wider than the window).
- **Draft card** (`AccelAnalysisScreen`, own curve only): the first felt step ("+X % gain for your fast movements, from a in/s, in full from b in/s"), or the reason no step is offered; always the caution that it is drawn from the movement and not measured. Buttons: save the stage as a copy of the file, test it blind.
- **Stage plan block** (`AccelIntroScreen`, right column under the own-curve box): title, stage k of n with step and anchors, sessions so far, the state in words (still getting used to it, two sessions eight hours apart needed, comparison missing, moved up, went back, last stage) and the caution that the thresholds are untested; "Play stage k" next to the other start buttons.
- **Result of a stage test** (`AccelResultScreen`): the stage can be saved as a copy of the player's file for every verdict; "clearly worse" is a warning text, not a block.
- **Runs use on the flow screens:** the finder intro replaces its "Starting point" card by the Runs use card; refine import, between rounds and the refine stage result show the one-line version (values, source below, the link on the right). Inside a running refine or curve run the line shows the values the run started with and says that a change applies from the next run. The link opens the settings with a return target, so Save, Back and Escape return to the screen it came from.

## Trainer visual style

Minimal, high contrast, low distraction: dark room, soft horizon, no decoration. Targets use the accent ramp (fill `#b5abfc`, ring accent 700), crosshair is a small neutral cross. HUD text is mono for numbers and sits in the 72 px margins, away from the center.

## Accessibility

- Colorblind-safe chart palette; series are also distinguished by dash style and labels; status uses text (OFF / ACTIVE / RAW) plus icon, not color alone.
- Interface text at least 16 px at 1920×1080; uppercase labels 14 px.
- Full keyboard navigation of menus and forms.

## i18n

LibGDX `I18NBundle`: `assets/i18n/messages.properties` (English default) and `messages_de.properties`. No user-visible strings in code. Layouts must tolerate German text length (about +30%). Numbers use the locale decimal separator for display only; stored values are locale-independent.

## Scene2D mapping

- UI stage uses a 1920×1080 virtual size with a fit/scaling viewport; the 3D room has its own viewport.
- Skin generated from `tokens.json` (colors, font sizes, radii) into `assets/ui/` (`uiskin.json` + atlas). Outlined buttons, cards and inputs use 9-patch drawables generated for radii 4/8/14.
- Fonts: Inter 4.1 (Regular, Medium) and JetBrains Mono 2.304 (Regular), both SIL OFL 1.1, are bundled in `app/src/main/resources/assets/fonts/` with their license files and rasterized with gdx-freetype at the exact pixel size of the framebuffer (`Fonts.kt`), rebuilt when the window height changes by more than 8%. Scaling a bitmap font instead blurs the text.
- Charts are custom actors drawn with `ShapeRenderer` (lines, filled band with alpha, dashed segments, dots), not Scene2D widgets.

### Range mode (M15c)

Original geometry, no assets of any game. The bot is a head sphere in sand (`#e6d9b8`) on an ochre torso and pelvis (`#c79a55`) with darker limbs (`#8f7048`), boxes only, turned to face the player; its proportions are those of the hit model (`BotShape`: 1.8 m, head 0.25 m, body capsule radius 0.2 m), so what is drawn is what is hit. The colours are warm so the bot stands out from the blue-grey room. In front of the player stands a low counter (1.6 x 0.4 m, 0.8 m high); on the floor, lit lines mark a lane every 10 degrees of bearing from -50 to +80 degrees and arcs at 5, 12.5 and 20 m (the near, middle and far distance of the profile). Corners and doorways are left out in Range mode. The HUD shows "Kills n   Shots m" at the top right instead of "Hits / Misses". Settings: the "Range mode" tile appears only for games with Range mode; when it is on, one row of two selects (speed, bots) sits under the tiles, and the update-check switch moved to the footer to make room.

## Deviations

| Mockup | Planned implementation | Reason |
|---|---|---|
| Label letter-spacing +10% | Approximated with spaced glyph layout for uppercase labels, or dropped | `BitmapFont` has no tracking |
| 2D circles as targets | Real 3D spheres, unlit accent color with an outline ring | The trainer is 3D; it must use the game's FOV |
| Icons (play, history, gear, hand …) | Line icons rendered into a texture atlas | Scene2D has no SVG; icon set and license to be confirmed |
| CSS hover/focus effects | Explicit hover, pressed, focus and disabled drawables (not shown in the mockups) | Scene2D needs them defined; designed to match the outlined style |
| Game tiles show CS2, Apex, Overwatch 2, Fortnite | Valorant ships first; other profiles are added only after yaw/FOV are verified | Spec: Valorant first, never guess values |
| Raw Accel row in the pre-test notice has an "Open Raw Accel" button | Manual checkbox "I checked: Raw Accel is off or linear" | Raw Accel exposes no query API and stores no readable state, so it cannot be detected; the Windows pointer acceleration setting is auto-detected |
| Language selector in onboarding | Omitted until i18n (M8) | No translations yet |
| Main menu lists Continue, Results, History | Only Start test and Settings | The other features arrive in M5-M8 |
| Logo and icons | Simple ring-and-dot logo drawn from shapes, no line icons yet | No icon set chosen |
| Label letter-spacing | Dropped | `BitmapFont` has no tracking |
| Fonts sized in pixels | Fonts are generated per framebuffer height in design units (`Fonts.kt`), shared by HUD and UI | Consistent size at any window size |
| Between rounds (1i): "Good round." headline, 85 % marker on the bar | Neutral headline "Round n complete", plain progress bar without marker, target stated in the text | No judgement of a single round; marker needs a custom actor |
| Results (1j): chart on the right of the numbers, Sensitivity / Acceleration tabs, legend | Numbers on top, the score chart below (fitted line, 95 % band, round dots, dashed optimum with its value), no tabs and no legend (the chart caption names the elements) | The chart needs the full width at 1080 high; acceleration has its own flow, see below |
| Menu: "Continue session", History | Results (R) and History (H) items, no "Continue session" | A new run merges with stored rounds, so there is nothing to continue |
| History (1m): full table, chart of cm/360° per session | The chart (one point per sensitivity session, x is the session number) and a table of the newest 6 sessions with a note for the rest; game filter as in the mockup; no scrolling | The skin has no scroll pane yet; the recommendation after each session is computed by refitting on all rounds of that game and DPI up to it, in the background |
| In-test HUD (1h) with progress bar below the timer and "Esc · pause" | Timer, scenario name, "Round n / N", "Esc aborts the round"; no bar, no pause | Aborting discards the round, a pause would taint it |
| Settings (1n) has metric weights only | A "Scenarios" block under the weights: four toggles for what a blind test plays; the weights decide how often | M6 scenarios need a way to be switched off |
| Between rounds (1i): hits, accuracy, time to hit | A caption with the scenario name; tracking shows "Time on target" instead of hits | Tracking has no hits |
| Acceleration is only a tab on the results view (1j) | Its own menu item (since M22f "Raw Accel", which opens the hub) with the finder intro (now "Find a curve"), calibration and test round screens, an analysis screen with a chart of the wanted gain per hand speed (dots, 95 % band) and the fitted curve, and a verdict screen with export and copy | The acceleration steps need rounds of their own and a decision before an export, so a tab on the results view would have nothing to show most of the time |
| Onboarding 3/4 (1e) has only the aim style cards; Settings (1n) has no flash row | The aim style step also asks "Turning away from flashes" (never / sometimes / often) below the cards, and the settings have a "Flash turn-away" row with the same three buttons (the flash scenario is switched by it, not by a tile) | The flash scenario (M10) needs the importance as a user setting without a fifth onboarding step |
| Language selector in Settings (1n) | A select box under Round length; the language applies at once as a preview and is stored on Save | English and German as in the mockup |
| Trainer: dark room, soft horizon, no decoration (frame 1h) | A minimal map: a darker floor at eye height, a ring wall, pillars at corners, door frames at doorways and a column under each head-sized target (accent ramp). Results (1j) has one more sentence under the confidence card: which axis the misses come from | M11: targets stand on the floor at head height and at corners, so crosshair placement can be trained; the sentence tells vertical from horizontal errors |
| Crosshair is a small neutral cross (frame 1h) | The player's own Valorant crosshair, drawn in screen pixels; new screen Crosshair (menu C): four columns (profiles, code and preview; color, outlines, dot; inner lines; outer lines), swatches for the eight presets, sliders with the game's ranges, bright / dark / trainer preview. The menu items are 72 high (gap 8) so that seven fit, with "Range mode" (M) between Start test and Results for games with a Range block | M12: players aim differently with an unfamiliar crosshair |
| Menu item "Acceleration" opens the finder intro; Last analysis, Last search and Refine are buttons on that intro | "Raw Accel" opens the hub; the finder intro (now "Find a curve") has no Refine button and no Last cards | One place for every Raw Accel tool and setting (M22f) |
| Row badges as chips | Badges are small uppercase text (ANALYSIS / INFO), no chip fill | Scene2D has no chip widget; the text carries the meaning |
| Formulas in the setting pages with Greek letters | Words ("angle") and the characters of the bundled fonts (× ÷ ° ·) | The font atlas has no theta or pi |
| Pad presets include Desk mat | Adopted (small / medium / large / desk mat / custom) | Extends the spec's three presets |

## Open design questions

See `docs/PROGRESS.md` ("Open questions"): pre-test system checks, "consistency" metric definition, session length vs "stop anytime". The export file dialog is decided: native open and save dialogs (LWJGL tinyfd, M22d) with the clipboard and the fixed folders as fallbacks.
