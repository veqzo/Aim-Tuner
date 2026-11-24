# M22 Raw Accel hub (plan, approved 2026-10-24)

## Kurzfassung (Deutsch)

Das Menü „Beschleunigung“ wird zu **„Raw Accel“** (Taste A bleibt) und öffnet einen Hub. Er enthält „Kurve finden“, „Meine Kurve verfeinern“, „Letzte Suche“, „Letzte Analyse“ und eine Liste **„Weitere Einstellungen“**. Ganz oben steht immer die Sensitivity, mit der alle Läufe spielen: Spiel-Sens, cm/360 und DPI aus der Einrichtung, dazu ein Link zu den Einstellungen.

Ich habe jede Einstellung von Raw Accel 1.7.1 im Quellcode geprüft (Tabelle unten). Keine weitere Einstellung bekommt einen eigenen Blindtest:
- Rotation, das Y/X-Verhältnis, die L/R- und U/D-Verhältnisse sowie Angle Snapping lassen sich aus den gespeicherten Runden **passiv** beurteilen: aus der Richtung und dem Fehler der ersten Bewegung zum Ziel bzw. aus dem Abspielen deiner Runden mit Snapping. Jede Analyse muss vorher im Harness einen Null-Gate und eine Erkennungstabelle bestehen. Fällt eine durch, wird sie zur Infoseite.
- Alle anderen Einstellungen bekommen eine **Infoseite** mit dem empfohlenen FPS-Wert und dem Grund, warum die App sie nicht misst.

Erste Belege aus deinen Daten: 1000 Hz, Median 1,4–2,2 Counts pro Report. Snapping wirkt pro Report und würde schon bei 5° 17 % (bei 10° 41 %) deiner vertikalen Counts in Range-Runden löschen. Meine Erwartung ist deshalb 0°; das Replay entscheidet.

Beim Verfeinern **fällt die Stufe LEVEL weg**. Spiel-Sens, Output-DPI, Verhältnisse, Rotation und die Kurve bei kleiner Geschwindigkeit bleiben in jeder Runde und im Export bitgenau gleich; ein Test garantiert das.

Das Modell wird zu einer zeilengenauen 2-D-Portierung des Treibers: Rotation → Snapping → Speed Cap → Domain → Geschwindigkeit/Lp → Glättung → Kurve mit Range-Gewicht → Output-DPI → Y/X → L/R/U/D → Carry. Dazu kommen die Modi power, jump und synchronous. Eine Refine-Runde bewegt die Ansicht damit so, wie Raw Accel es in Valorant tun würde.

Import und Export bekommen native Dateidialoge (LWJGL tinyfd 3.3.3, passend zur LWJGL-Version von LibGDX). Zwischenablage und Ordner bleiben als Ausweg.

Sieben Meilensteine M22a–M22g. a und d können parallel laufen.

## Outcome (M22a-g)

Status after M22g: everything below the plan text was built as planned on branch `feature/m22-raw-accel-hub`, with the deviations listed here. The plan text below is kept unchanged as the record of what was decided before the work; where it differs (for example the analysis code lives in `core/accel/settings`, not in `core/rawaccel/analysis`), this section and `docs/PROGRESS.md` are the truth. Details and gate tables: `docs/ALGORITHMS.md` ("Raw Accel driver model (M22)", "Passive setting analyses (M22e)", "Refine my Raw Accel curve").

**Per setting** (gate = `PassiveSettingsGateTest`, 200 runs per row; "real-SD noise" = the setting with the hand noise scaled to the spread of the stored rounds):

| Setting | Final decision | Key evidence |
|---|---|---|
| Rotation | Analysis, recommends; tickable into the patched copy | null 0 %; ±3, ±6, ±10 degrees: 100 % right sign and within 2 degrees, in all four settings |
| Y/X | Analysis, recommends; tickable | null 0 % (5 % at real-SD noise); g_y 0.8 / 1.25 found in 100 % / 100 % (75 % / 100 % at real-SD noise) |
| L/R | Analysis, recommends; tickable | null 0 %; 0.85 / 1.15 found in 100 % / 100 % in all settings |
| U/D | Info page, shows its measured number only | null 8 % at real-SD noise (target <= 5 %), so no recommendation; 0 %, 0 %, 2 % in the other settings |
| Angle snapping | Info page, recommends 0 degrees, shows the replay | recommends 0 in 100 % of runs in every setting; on the five real flick rounds snap 10 degrees gives +46 % vertical error, 2 to 5 degrees no effect |
| Input speed cap | Info, recommend off, shows your speed percentiles | speed profile over 213,282 reports: p50 2.5, p99 23.9 in/s |
| Smoothing, stretches, whole/by component, Lp norm, polling | Info pages, recommend 0, 1/1, whole with 2, automatic | below the measurability floor (a 15 % multiplier change moves the score about 1 point against a round SD of 13) |
| Output DPI, device DPI | Base: shown, never changed by refine | `RefineBaseInvariantTest`: 2502 random cases over 409 profiles, 158 engine runs |

**Refine.** LEVEL is gone; refine changes only curve-shape fields and the base stays identical in every round and in the export. The gate holds (REFINE_NULL exports 0 of 500, harmful exports 0 in every model), but no preference is found: with the user's profile only 3.7 % (no flash) or 9.5 % (flash) of the simulated hand's gain evaluations lie above the offset, the imported and the preferred curve differ there by at most 0.0026 in the mean multiplier, the wanted side is hit in 47.5 to 56.0 % of the runs (chance) and nothing is exported, even for preferences of x0.5 and x2. **User decision: Refine stays in the hub, with an honest note** that it changes a curve only after a clear blind win, which did not happen in simulation. Untested: a profile whose rise lies at 15-25 in/s, a hand with faster flicks, real hands.

**Answers to the open questions.**

1. Passive findings in the export: opt-in ticks for rotation, Y/X and L/R only, labelled "not blind-tested", off by default.
2. A failed gate becomes an info page: applied to U/D (number only). No new finder.
3. Power mode: played and blind-tested, no refine stages.
4. The last imported file path is persisted (path only, never the content).
5. Data: only the 10 stored rounds (5 flick, 5 range) were available; the real-data check uses the five flick rounds (112 usable reaches), Y/X and U/D say "not enough data" on them.
6. Gate targets were kept (null <= 5 %, detection >= 70-80 %); no threshold was changed to pass.

## Evidence used (all reproducible)

- **Source:** `a1xd/rawaccel` tag v1.7.1, files `common/rawaccel.hpp`, `rawaccel-base.hpp`, `rawaccel-validate.hpp`, `accel-*.hpp`, `math-vec2.hpp`, `driver/driver.cpp`, `wrapper/wrapper.cpp`. Line numbers below refer to v1.7.1.
- **Your stored data** (read-only query of `%APPDATA%/AimTune/aimtune.db`): 10 rounds (5 flick, 5 range), 213k reports, all at 800 DPI.
  - Report interval: p5 0.97 ms, median 1.00 ms, p95 3.0 ms, so 1000 Hz.
  - Median report size: 1.4 counts (flick), 2.2 counts (range).
  - Hand speed: p50 2.5 in/s, p90 11.3, p99 23.9, p99.9 35.6. One outlier of 260 in/s, probably a timing artifact.
  - So your offset of 10 in/s is crossed by only about 10 % of reports, and your cap at 62.5 in/s lies above 99.9 % of them.
  - **[Limit]** Only one stored session is left, so all passive analyses are designed for growing data and must say "not enough data" honestly.
- **Measurability floor (M18 gate):** a 15 % change of the whole multiplier moves the simulated score by about 1 point against a round SD of 13. A setting whose effect on the multiplier is smaller than that cannot be found blind in 12-round stages. This is the main argument against new finders.

## Driver processing order (v1.7.1)

Per report, in `driver.cpp:84-128` and `rawaccel.hpp:313-423`:

| # | Step | Formula | Source |
|---|---|---|---|
| 0 | Time per report | `t = clamp(ticks/num_packets, min, max)`. With "constant time" and a rate: `t = 1000/rate`. With a rate: `min = 1000/rate` | driver.cpp:84-94, 378-392 |
| 0b | DPI normalization | `dpi_factor = 1000/dpi` (1 if dpi = 0) | driver.cpp:376 |
| 1 | Rotation | `in = (x·cosθ − y·sinθ, x·sinθ + y·cosθ)` | rawaccel.hpp:322, math-vec2.hpp:14-26 |
| 2 | Reference angle and snap | `a = atan(|y/x|)`. If `a > 90°−s`: `in = (0, ±|in|)`; if `a < s`: `in = (±|in|, 0)`. Only computed when snap or a directional range weight is on | rawaccel.hpp:324-344 |
| 3 | Input speed cap | `v = |in|·dpi_factor/t`; `in *= clamp(v, 0, max)/v`. This scales the **movement itself** | rawaccel.hpp:346-351; `speed_min` is `[JsonIgnore]`, wrapper.cpp:242 |
| 4 | Domain stretch | `w = (|x|·dx_w, |y|·dy_w)·dpi_factor/t` | rawaccel.hpp:353-356 |
| 5a | Whole: speed | Lp norm (2 = Euclidean; ≥16 or ≤0 = max), then optional input-speed smoothing | rawaccel.hpp:199-255 |
| 5b | Whole: range weight | `weight = rx + (2/π)·a·(ry − rx)`; `scale = 1 + (f_x(v) − 1)·weight`. **Only `accel_x` is used** | rawaccel.hpp:383-390, 451 |
| 5c | By component | per axis `scale_i = 1 + (f_i(w_i) − 1)·r_i`, each axis with its own args and smoothers | rawaccel.hpp:358-379 |
| 6 | Scale smoothing | simple EMA of the scale | rawaccel.hpp:62-91, 392-395 |
| 7 | Apply and output smoothing | `in *= scale`; output smoothing = linear EMA (with trend) of the output magnitude | rawaccel.hpp:96-163, 397-409 |
| 8 | Output DPI and Y/X | `x *= outDPI/1000·dpi_factor`; `y *= (same)·yx` | rawaccel.hpp:412-414, 429 |
| 9 | L/R, U/D | `if x<0: x *= lr`; `if y<0: y *= ud` (left and up) | rawaccel.hpp:416-422 |
| 10 | Carry | `out = trunc(in + carry)`; the remainder carries over while `|carry| < 1` | driver.cpp:115-128, 39-42 |

## Settings audit

Legend: **F** = blind finder, **P** = passive analysis of stored rounds (no new rounds), **I** = info page, **B** = part of the base (shown on the hub, never changed by refine).

| Setting (JSON key) | Effect, order step | FPS relevance | Judgeable how? | Decision and reason |
|---|---|---|---|---|
| Output DPI | uniform factor, step 8 | it *is* the sensitivity | the sensitivity finder (main test) | **B**. Refine must not change it (LEVEL is removed) |
| Mode + curve args (whole/horizontal) | the curve, step 5 | yes | existing finders | **F (exists)**: "Find a curve" and "Refine my curve" |
| Vertical accel parameters | used only in by-component mode, step 5c | low | — | **I**: with whole mode (recommended) they do nothing (rawaccel.hpp:390) |
| Gain / Velocity, cap mode | how the args are read | part of the curve | refine keeps them | **I** (inside the curve page) |
| Power, jump, synchronous modes | curve shapes | like any curve | refine | model them exactly. Refine stages only where the base invariant holds (see Design) |
| Domain stretch x/y | per-axis speed weight, step 4 | low to medium: changes when vertical movement reaches the curve | needs vertical reaches at curve speeds. Your data: 78 % (range) and 56 % (flick) of reports lie within 5° of horizontal | **I**: recommend 1/1. Its effect only touches the vertical speed share, which is below the measurability floor. [A] |
| Range stretch x/y | per-axis or angle-interpolated curve effect, step 5b/5c | low to medium | as above | **I**: recommend 1/1, same reason |
| Whole vs by component | Euclidean speed vs per axis | medium: by component gives diagonals less acceleration and makes the response depend on direction | not measurable at our share of diagonals | **I**: recommend whole (direction-independent response) |
| Lp norm | speed metric in whole mode | low: only changes diagonals | as above | **I**: recommend 2 |
| Input-speed smoothing half-life | linear EMA (with trend) of the speed fed to the curve | low: less multiplier jitter, but the acceleration reacts later | the multiplier change is below the measurability floor | **I**: recommend 0. The page shows a replay of your reports: how much the multiplier jitters at your curve and how much a half-life would delay it |
| Scale smoothing half-life | EMA of the multiplier | low | as above | **I**: recommend 0 |
| Output smoothing half-life | EMA of the **output magnitude**: adds lag to every movement | high, and harmful | a lag is a known harm; no test needed | **I**: recommend 0. Warn if your file has it on |
| Y/X output ratio | vertical sensitivity factor, step 8 | medium | **yes**: the vertical vs horizontal over/undershoot of first movements at head-height targets (the metrics exist, the crosshair page uses them) | **P** (gated). If the gate fails → **I** |
| L/R ratio | factor on leftward movement, step 9 | low to medium (an asymmetric hand) | **yes**: signed first-movement error, left vs right reaches | **P** (gated) |
| U/D ratio | factor on upward movement, step 9 | low | **yes**: up vs down reaches. Vertical reaches are rare, so the gate decides | **P** (gated) |
| Degrees of rotation | rotates the input vector, step 1 | medium (a rotated grip or sensor) | **yes**: the signed angle between the first-movement direction and the target direction. Raw counts alone are confounded by the target layout: flick +0.8°, range −0.3°, same sign left and right, so the scenario geometry drives it. A target-relative analysis is required | **P** (gated with simulated rotated hands) |
| Degrees of angle snapping | per-report snap to an axis, step 2 | medium and **harmful** at 1 kHz: per-report angles are mostly quantization. Your data: 5° deletes 1.2 % / 16.6 % of vertical counts (flick / range), 10° deletes 10 % / 41 % | **yes**: replay the stored reaches with snapping (open loop) and compare the vertical error at the end of the reach | **P**: expected result 0°. No blind test: the replay answers whether snapping removes drift or corrections, and a blind test would be under the measurability floor anyway |
| Input Speed Cap | clamps the **movement** speed, step 3 | harmful if below your peak speed | your stored peak speeds (p99.9 35.6 in/s) | **I**: recommend 0 (off). The page shows your speed percentiles |
| Device DPI | speed normalization, step 0b | correctness | already checked on import | **B / check**: must equal AimTune's DPI (existing warning) |
| Polling rate, "constant time interval" | time per report, step 0 | low: with locked time the speed gets less jittery | modelled; not judged | **I**: recommend automatic (0, off). Modelled exactly |
| minimumTime / maximumTime | time clamp | low | modelled | **I** (advanced, modelled) |
| disable, setExtraInfo, device→profile map | the device is off / extra info is written / which profile applies | correctness only | import warnings | **I / warning**: setExtraInfo does not change the output (driver.cpp:98-104) |

**Answer to the angle-snapping question:** stored rounds can answer it, so the decision is passive. Even before the replay, the evidence points to 0°. At 1 kHz with reports of about 2 counts, any snap ≥ 5° deletes a large share of the vertical counts during horizontal movement (17–41 % in Range). Whether those counts were drift or corrections is what the replay measures. A dedicated blind test is not justified.

**Answer to the rotation question:** passive, but only target-relative. The first movement of each reach is compared with the target direction. Harness gate: simulated hands rotated by 0, ±3, ±6 and ±10°.

## Design

### Hub UI (`RawAccelHubScreen`)

> Superseded by ADR 0006 (M24): `RawAccelHubScreen` is removed; the hub content is the back of the CURVE module on the dashboard.

- **Main menu:** "Acceleration / Beschleunigung" becomes **"Raw Accel"** (same text in DE), with the subtitle "Curve, refine and every other setting" / "Kurve, Verfeinern und alle weiteren Einstellungen". Key **A** stays (R is taken by Results).
- **Hub, top card "Runs use":** game name, game sensitivity, cm/360°, DPI, with a source line (settings, or the finder's optimum when that box is ticked in "Find a curve"). Button **"Change in Settings"** opens the settings screen with a return target, so Save and Back return to the hub. No sensitivity field.
- **Section "Curve":** Find a curve (the existing `AccelIntroScreen`, without the Refine button and the Last cards), Refine my curve, Last search, Last analysis.
- **Section "Other settings":** one row per audit row of type P or I. Each row shows a badge (Analysis / Info) and a one-line state, e.g. "Recommended 0° · from 10 rounds" or "Not enough data".
  - A row opens a generic `RawAccelSettingScreen`: what it does (in words, plus the formula), your current value (if a file was imported), the recommended value, and why. For P rows also the result with its range and data count; for I rows "why the app does not measure it".
- **Imported profile:** the hub shows the last imported profile (name and file). It is kept for the session; persisting the path is an open question.
- **Layout:** two columns at ≥ 1080p, one column below 1280 px width. `Widgets.frame` scrolls. Screenshot steps `hub`, `hub-setting-info`, `hub-setting-passive` in EN and DE.
- Every flow screen (finder intro, refine import, between rounds, stage result) shows the "Runs use" line with the same settings link.

### Data model and storage

- **core:** `RawAccelProfile` becomes the full profile: `accelX`, `accelY`, `speedArgs` (whole, lpNorm, 3 half-lives), domain and range weights, outputDpi, yx/lr/ud, rotation, snap, speedCap, plus `DeviceConfig` (dpi, pollingRate, pollTimeLock, timeMin, timeMax).
- **New `RawAccelModifier`:** a stateful line-by-line port of the order table above. `modify(MouseEvent): MouseEvent` returns integer counts with the carry.
- **New `MouseModifier` interface:** replaces the scalar `AccelProcessor.scaleFor` at the four call sites `Trainer.kt:138`, `RoundRecording.kt:46`, `HeadlessRound.kt:77` and `SimulatedAimer.kt:571`.
  - `AccelProcessor` (the finder's `AccelCurve`) implements it with the current continuous behaviour, unchanged, so the finder stays bit-identical.
  - `ViewTrack.scales` becomes `|out|/|in|`.
  - `RoundRecording.accel` becomes an `AccelModel` factory, because a modifier holds state.
- **Storage:** a new `AccelSpec` form `rawaccel2;<compact JSON>` for the full profile. `rawaccel1` rounds still parse with their old meaning.
  - For `rawaccel2` rounds the `cm360` column is the game cm/360°. The output-DPI factor sits inside the modifier, so the carry acts on the real counts.
  - No schema change: `round.accel` is TEXT.
- **Import (`RawAccelImport`):** the refusal list shrinks to "Raw Accel itself would refuse it". Everything is played exactly. Warnings: device disabled, a different device→profile mapping, setExtraInfo, a DPI mismatch (existing), output smoothing on.

### Refine without base changes

- `RefineParameter` = {HIGH_GAIN, TRANSITION}. LEVEL is deleted, along with its search range, the `adjusted(LEVEL)` branch, the engine stage, the screen texts and the LEVEL models in `RefineGateTest`.
- **Base invariant (new `RefineBaseInvariantTest`, property-based over every mode × stage × factor in range × random valid profiles):**
  1. Every field outside the curve-shape whitelist of `accelX`/`accelY` is equal.
  2. The game sensitivity of every `RefineRound` equals the settings value.
  3. The multiplier as speed approaches 0 is unchanged (|Δ| ≤ 1e-12).
  4. The patched export differs from the input file only in whitelisted keys of the chosen profile.
- **Stage mappings:**
  - classic and natural: as today.
  - **lut:** HIGH_GAIN `y' = y₀ + (y − y₀)·s`; TRANSITION `x' = x·t`. This matters because finder v2 exports a lut.
  - **jump:** HIGH_GAIN `capY − 1` × s; TRANSITION `capX` × t.
  - **synchronous:** TRANSITION `syncSpeed` × t only; motivity would change the low-speed floor `1/motivity`.
  - **power:** no stage (scale and the io cap move the low-speed multiplier). Played and blind-tested only; the hub says so.
  - **by component:** the same factor on both axes.
- `RefineEngine`, `RefineSearch` and the screens lose the LEVEL texts. `PlayedProfile` / `played()` are replaced by `RefineRound(profile, gameCm360)`.

### File dialogs

- **Library:** LWJGL 3.3.3 (shipped by gdx-backend-lwjgl3 1.14.2) has no file dialog in the runtime classpath. `org.lwjgl:lwjgl-tinyfd:3.3.3` with `natives-windows` exists on Maven Central (checked: HTTP 200).
  - I chose tinyfd over `lwjgl-nfd` because it needs no init/COM lifecycle and offers open and save with filters, which is all we need.
  - The exact signatures (`tinyfd_openFileDialog`, `tinyfd_saveFileDialog`, filter `PointerBuffer`) are verified against the LWJGL 3.3.3 javadoc in M22d before coding (project rule).
- **Code:** a `FileDialogs` interface in `app` with `TinyfdFileDialogs` and a `FakeFileDialogs` for the screenshot tour and tests.
  - Release the cursor before the call; the call is modal on the render thread.
  - Cancel (null) shows nothing. Unreadable, too large (> 1 MB), or not JSON / not a Raw Accel file each shows its own EN/DE message.
- **Save as:** the default path is `<data dir>/exports/rawaccel/<name>.json`. The fixed-folder button stays as the fallback, and so do the clipboard button and the `imports/` folder.

### Model extension

- New `core/accel` files: `RawAccelModifier` (order table), `RawAccelSmoothers` (simple and linear EMA, trend half-lives 1.25 / 0.7), `PowerCurve`, `JumpCurve`, `SynchronousCurve` (legacy closed form; gain via the float table of `accel-lookup.hpp` `fp_rep_range{-3, 9, 8}`), each formula citing file:line.
- The existing `RawAccelCurve` gets the new modes in its `when`.
- **Reference tests (as in M17):** a separate literal test-side port of `modify()`, `driver.cpp` carry/time and each mode. Fixed report streams (horizontal, diagonal, near-axis, left/up, pauses, 8 kHz bursts) checked to 1e-9 (float tables 1e-6), plus your profile end to end.

## Milestones

### M22a Exact 2-D driver model (core only) — L — ∥ with M22d
- **Scope:** full `RawAccelProfile`, `RawAccelModifier`, smoothers, power/jump/synchronous, carry, time and poll lock; `AccelSpec rawaccel2`.
- **Accept:** reference tests for every row of the order table and every mode and cap mode; your file round-trips through `rawaccel2`; validation mirrors `rawaccel-validate.hpp`.
- **Tests and gates:** 1e-9 against the port; no change to existing tests.
- **Risks:** synchronous gain table precision (float), Lp/max-norm edge cases, `atan` at x = 0.

### M22b Pipeline and import (core, storage, app) — M
- **Scope:** `MouseModifier` at the 4 sites; `AccelModel`; import accepts everything; the trainer and the headless runner play the full profile; warnings.
- **Accept:** a refine round with your file reproduces the reference output counts report by report (replay of a stored round through `RawAccelModifier` vs the test port). Finder rounds are bit-identical: `AccelPipelineTest`, `AccelRunTest` and the M16/M19/M20 gate tables are unchanged. Import tests for each formerly refused setting.
- **Risks:** `ViewTrack.scales` consumers (analysis) and the simulated aimer planning with a 2-D modifier.

### M22c Refine without LEVEL — M
- **Scope:** delete LEVEL; stage mappings for lut, jump and synchronous; by component; `RefineBaseInvariantTest`; screens and i18n; `RefineGateTest` re-run.
- **Accept:** the invariant test passes over at least 2000 random cases. The export patches only whitelisted keys.
- **Gates (500 runs):** REFINE_NULL exports ≤ 5 % (was 0/500); harmful exports = 0 in every model; CAP ±25 and TRANSITION ±50 % reported (with direction and carried rates), no target.
- **Risks:** fewer stages, so with LEVEL gone a profile like noaccel has nothing to refine. The hub must say this.

### M22d File dialogs — S/M — ∥ with M22a
- **Scope:** tinyfd dependency (version catalog, natives-windows), `FileDialogs`, open on the import screen, save-as on the result screens, messages EN/DE, fake for screenshots; check that `jpackage` still runs and the dialog works from the app image.
- **Accept:** cancel, unreadable, wrong type and valid are each covered by a unit test through the fake. A manual check by you in the app image.
- **Risks:** a modal dialog on the GLFW thread with a captured cursor; the tinyfd quote/encoding limits on Windows (titles stay ASCII-safe).

### M22e Passive analyses and harness — L (after M22a)
- **Scope:**
  - Simulated hand extensions: a grip rotation θ, a per-axis gain preference (wants vertical gain g_y), and left/right and up/down gain bias.
  - `core/rawaccel/analysis`: `RotationAnalysis`, `AxisRatioAnalysis` (Y/X, L/R, U/D), `SnapReplay`, `SpeedProfile` (percentiles for the info pages).
  - Each analysis returns a recommendation with a range, or "not enough data", with a minimum data count.
- **Gates (per analysis, 200 runs per row, data volume = 20 flick + 10 range rounds) [targets are mine, tell me if you want others]:**
  - Rotation null (θ = 0): non-zero recommendation ≤ 5 %. Detection |θ| = 6° and 10°: correct sign and |error| ≤ 2° in ≥ 80 %. |θ| = 3°: report only.
  - Y/X null ≤ 5 %. Detection g_y = 0.8 / 1.25: correct direction ≥ 70 %. L/R and U/D the same with a 15 % bias.
  - Snap replay: a hand without drift recommends 0° in ≥ 95 %. A hand with injected vertical drift: report only.
  - **If a gate fails:** that setting becomes an info page that shows the measured numbers without a recommendation. No threshold is changed to pass.
- **Risks:** few vertical reaches at head-height targets (U/D and Y/X power); the first movement is a closed-loop correction; the replay is open loop (the player would adapt) [A].

### M22f Hub UI, export merge, i18n, screenshots — L (after M22c, M22e)
- **Scope:** menu rename; `RawAccelHubScreen`, `RawAccelSettingScreen`, "Runs use" card and line with the settings return target; trim `AccelIntroScreen`; the refine result lists changed fields (merged findings, see open question 1); i18n EN/DE; screenshot tour EN/DE (hub, setting info, setting passive, import with dialog button).
- **Accept:** the screenshots fit at 1920×1080 and 1280×720 without clipping (tour at both sizes); every new string exists in EN and DE (existing bundle test); you test it hands-on.
- **Risks:** screen count and layout at 720p.

### M22g Docs and closing — S
- ALGORITHMS ("Raw Accel driver model", "Refine without base changes", "Passive setting analyses" with the gate tables), SPEC (hub, settings, hard rules unchanged), DESIGN (hub, deviations), ARCHITECTURE (`MouseModifier`, `AccelModel`, `FileDialogs`), README (feature table), PROGRESS. Each milestone already updates its own doc sections; g is the consistency pass.
- **Accept:** `./gradlew build` is green; the gate numbers in the docs equal the test output.

**Order:** a ∥ d → b → c ∥ e → f → g. Each milestone ends with `./gradlew build`, my review, one commit, and a PROGRESS update.

## Open questions for you

1. **Passive findings in the export:** M16 rule: only blind-tested changes are exported. My proposal: passive recommendations are shown with the value to enter in Raw Accel. You may tick them into the patched copy; they are labelled "not blind-tested" and are off by default. Alternative: never export them.
2. **If the Y/X (or L/R, U/D) gate fails:** my proposal is an info page with the numbers, no new finder. A blind Y/X stage would need vertical target spread, which contradicts the realistic head-height targets (M11). OK?
3. **Power mode refine:** play and blind-test only, no stages (they would move the base). OK?
4. **Remember the last imported file path** in the settings, so the hub shows your profile at the next start? (Proposal: yes, path only, never the content.)
5. **Data:** the database now holds only 10 rounds (one session). Do you have an older `aimtune.db` with the earlier curve runs? The passive pages work with little data but will mostly say "not enough data" at first.
6. **Gate targets in M22e:** are 5 % null and 70–80 % detection acceptable, as in earlier milestones?
