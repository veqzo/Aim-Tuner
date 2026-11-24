# M15 Range mode

Status: **step 5 done as M15c and M15d (default round type, menu entry; see the end)**; earlier status: **plan approved; corrected after the first recordings** (see "Corrections after the first recordings"). Sensitivity 0.335. Nothing below is measured yet; every value in this file is a plan or a guess until `tools/range-re/out/range-spec.json` exists.

## Goal

A "Range mode" that rebuilds the function of Valorant's shooting range station **Skills Test** (the prompt assumed 25 bots per run and Easy / Medium / Hard; the recordings show the mode **Eliminate 100** with a TIME counter and a REMAINING counter, Bot Armor on and Infinite Ammo on, see below). It becomes the default round type for the blind sensitivity fit and the curve search; flick and flash rounds stay as optional scenarios. It also closes R1b (head size, eye height and elevation measured instead of assumed).

## Hard rules

The repo is going public and the game runs under Vanguard.

- Never: read game memory; hook, inject into or debug the client; unpack, decrypt or extract game files, maps, models, textures or sounds; touch Vanguard; automate input into the game.
- Allowed sources only: the user's own recordings and screenshots, the user's manual measurements, public documentation with every source cited.
- Clean room: geometry, timing and behaviour are copied as closely as measured (lanes, distances, counter, bot positions and sizes, strafing, spawn timing, the 25-bot run). The look is inspired, not copied: same proportions, a warm sandstone and timber palette, glowing lane lines, a score board, all from original geometry and procedural textures. No Riot bot design, logo, font, HUD, texture or sound. The bot is an original humanoid with the measured head and body proportions, so hitboxes stay exact. In the UI it is called "Range mode".
- The REA skill is used only on recordings and docs, never on binaries or the running game.

## What gets measured

All values go to `tools/range-re/out/range-spec.json`, each with `source` (clip:frame range or URL), `n`, a spread and `verified`.

| Value | Clip | Method |
|---|---|---|
| Calibration of pixel to angle | 1 (slow 360° turn) | known yaw 0.07, sensitivity and DPI; `UnitChain` |
| Bots per run (expected 25), run start and end | 3 (full runs) | frame labelling, on-screen counter |
| Spawn positions as distance, yaw, elevation | 2 (static views) | bounding boxes → angles; distance only from floor markers or floor geometry, never from head size (that would be circular with R1b) |
| Arena geometry: counter height and position, lane width, back-wall distance, floor lines | 2, 4, 5 | floor markers and edges → angles → metres |
| Spawn intervals, bot lifetime per difficulty | 2, 3 | detection timestamps |
| Strafe speed, range, turn pattern | 2 | position tracking over time |
| Armored and unarmored hit rules | 3, 2 | what is visible; else `verified: false` |
| Head and body angular size per distance | 4 (head at known markers) | bounding boxes; closes R1b |
| Eye height (stand, crouch), head height | 5 | geometry from known marker distances |
| Fields of the end-of-run stats screen | 3 | cropped frames |

Conversion: 1920x1080, horizontal FOV 103, yaw 0.07 deg/count at sensitivity 1, 800 DPI. Inputs also noted per clip: aspect ratio and letterbox, crosshair centre. Recording sensitivity: 0.335 (confirmed by the user; 0.435 from the prompt is not used). Recordings and the fit use Infinite Ammo on. The Python `UnitChain` mirrors the Kotlin unit chain (`UnitChainTest` in `app/src/test`), with a parity test on shared sample values. Every public source is cited with its date.

## Corrections after the first recordings

The user's first two clips (`clips/`, 1920x1080, 30 fps, 800 DPI, sensitivity 0.335; OBS probably scaled a 2560x1440 game down) show a different test than the prompt assumed:

- The panel reads **Challenge: Eliminate 100**, Bot Armor **enabled**, Infinite Ammo **enabled** ("Press F3 change settings"). There is no Easy / Medium / Hard in the clips, and no 25-bot run.
- The board counts REMAINING down from 100 and shows a TIME counter. A run therefore ends after 100 bots, not 25; the old "25 bots" is dropped until a source shows it.
- The user confirmed: the mode to copy is Eliminate 100, and the first setting is this one (armor on).
- 30 fps limits timing to about ±33 ms and makes fast strafes coarse; the scripts report that uncertainty. Other difficulty settings only come back if the user records them.
- Clips so far: 1 slow turn (14 s), 2 an Eliminate 100 run (67 s) with one crouch. There is no head-at-marker clip yet, so R1b stays open (head size can come from the turn clip's calibration only if a known distance is visible).

## First measurement pass (preliminary)

Scripts in `tools/range-re/`, run on the two clips (`out/turn.json`, `out/range-spec.json`; frames and detections are cached in the ignored `cache/`):

- **Turn clip:** the integrated image shift gives a total of 337° (peak 345°) for what looks like one full turn. The 6 % shortfall fits phase correlation losing large shifts at up to 300°/s, so the FOV 103 / width assumption is plausible but not proven.
- **Bot detection:** bots are found by their purple outline (OpenCV hue 128 to 168). Checked on a contact sheet of four frames: the boxes sit on bots. Far bots are small (about 1.5° tall).
- **Tracking is too weak:** 7 tracks in 67 s, but the counter fell by about 20 (100 to roughly 78 at 52 s). The tracker needs at least 12 frames per bot and loses short-lived or fast bots, so lifetime (median 0.9 s), gap between bots (median 5 s) and spawn bearing are biased and not usable yet. Strafe speed has only 2 tracks.
- **Not measurable from these clips:** head size per distance and eye height (no known distance markers, one crouch), run end, stats screen. Distances need floor markers, not head size.

Next for step 3: read the REMAINING counter per frame (cropped digits, template match) to count kills and timestamp them, lower the track minimum, and ask the user for a clip with a bot standing still at marked distances.

## Decisions the review added

- **One setting first.** Measure and integrate a single setting (Eliminate 100, Bot Armor on, Infinite Ammo on) first; other settings are added after the gate passes.
- **Noise model.** A 100-bot run is one long, correlated observation. Before integration, decide whether a run feeds the optimizer as one value (score) or as per-bot samples, and check the repeat-round and confidence logic (`SensitivityOptimizer`) against it, including fatigue and learning inside a run.
- **Gate validity.** `SimulatedPlayer` and `NoiseModel` were tuned on flick targets. Strafing bots and kill timing need simulator parameters checked against the measured data; otherwise "no worse than flick-only" proves little.
- **Git hygiene.** Raw clips, frames and crops stay out of git (`tools/range-re/out/` frames and the input videos are ignored). Only derived numbers (`range-spec.json`, small CSVs) are committed.

## Module plan

| Part | Plan | Existing code |
|---|---|---|
| Profile | `trainer.range` in `games/valorant.json` with DTO, loader and tests; measured head and eye values replace the assumed ones (R1b), `verified` set where measured | `GameProfiles.kt` (private DTOs), `GameProfile.kt`, `GameProfilesTest.kt` |
| Scenario | `RangeScenario`, `ScenarioKind.RANGE`; seeded spawns from the spec; strafing via `TargetPath`; elevation from the measured distribution instead of `ElevationMix` when present | `core/scenario/{ScenarioKind,Scenario,FlickScenario,TargetPath,ElevationMix}.kt` |
| Arena | range-like layout from original geometry (counter, lanes with glowing lines, back wall, score board) using the measured lane width and distances | `ArenaLayout.kt`, `TrainerScene.buildArena` |
| Metrics | existing first reach, first-shot accuracy, time to hit, horizontal/vertical error; add time-to-kill per bot and a run score; bump `METRICS_VERSION` (3) only if stored metrics change | `RoundMetrics.kt`, `RoundRepository.kt` |
| Pipeline | RANGE in `ScenarioSchedule` as default; strength search and A/B use it (range rules like `MAX_WITH_FLASH`); menu entry "Range mode" with flow (a) blind sensitivity fit and (b) curve search, blind A/B, LUT export | `ScenarioSchedule.kt`, `MainMenuScreen`, `AppContext.startBlindTest/startAccelRun`, `AccelRun`, `StrengthSearch`, `StoredSearches` |
| Simulator | simulated player plays `RangeScenario` in `HeadlessRound`; `TrainerHarnessTest` covers it | `HeadlessRound.of`, `TrainerHarnessTest` |
| Gate | M14 `GateHarness` with the 4 player models; range must not raise flat false positives, and the sensitivity error must not be worse than flick-only; tables go to ALGORITHMS.md "M15" | `core/src/test/.../harness/review/` |

## Measurement tooling

`tools/range-re/` holds Python scripts (ffmpeg, OpenCV): frame extraction, bot detection by colour threshold or template match, bounding boxes to angles through `UnitChain`, tracking for strafe speed, spawn and despawn timestamps. Output is CSV and JSON in `out/`. Everything is cached on disk keyed by a hash of the input. Models see only summaries (median, IQR, counts). Haiku labelling is limited to crops of at most about 512 px and about 30 images per batch. If a script value and the public docs differ by more than 10 %, one Sonnet pass looks at about 5 crops before the user is asked. There is no Python in the repo yet, so the tooling is new.

## Phases

1. Plan (this file), then stop for approval.
2. `tools/range-re/RECORDING.md`: recording kit for the user (OBS, settings to note, crosshair centre, five clips).
3. Measurement scripts and `range-spec.json`.
4. REA pass: run state machine (start, spawn, kill or timeout, end), difficulty rules, hit and kill rules; guesses are marked `verified: false`.
5. Integration, one Sonnet subagent per part (table above), then the gate.
6. Docs (SPEC, ARCHITECTURE, ALGORITHMS, DESIGN, README clean-room note, PROGRESS) and the screenshot tour in EN and DE.

All work is on `feature/m15-range-mode`; nothing is merged without the user's OK. Model use: Sonnet for the work, Opus at most twice (this plan, the final spec check).

## Open risks

- **Sensitivity:** the user confirmed 0.335 for the recordings. The first setting is Medium, Eliminate 100, strafing bots, Bot Armor on (Speed Easy/Medium/Hard exists in the F3 panel according to valorant.fandom.com/wiki/Range; the recording kit captures all three).
- **Recording quality:** the measurements are only as good as the clips; colour-threshold detection can fail with armor, lighting or effects.
- **Invisible rules:** armor and hit rules may not be observable; they stay `verified: false`.
- **Lifetime and spawn timing** may depend on difficulty in ways static views do not show; full runs (clip 3) are the check.
- **Public docs** for the range may be thin or outdated.
- **Look:** a close likeness drifts toward copying; the bot and the props stay original.
- **Scope:** six settings exist; see "One setting first".

## Second measurement pass (clips of 2026-10-22, 1920x1080, 60 fps)

Preliminary, `verified: false`; details in `tools/range-re/out/range-summary.md` (git-ignored with the rest of `out/`).

- Panel (read from the frame): Speed Easy/Medium/Hard exists but none was selected; Streak Eliminate 100; Bots Strafe (other option: Reset); Bot Amount 1/5/10 (5 selected); Bot Armor on; Infinite Ammo on.
- Unshot bots do not despawn (one strafing bot stayed 44 s), so lifetime and spawn timing come from played runs only.
- Strafe: move-stop-move, moving 12 deg/s median (p25 8, p75 16; about 2.6 m/s if the bot is 12 m away, distance assumed from a 1.8 m bot), moving segments 0.36 s, stops 0.2 s, about half of the time stopped.
- Played run: a new bot about 1.8 s after the previous one (p25 1.25, p75 2.46, n = 19), a shot bot lives 1.4 s median; 23 kills in 39 s; bearing -51 to +81 deg; distance roughly 3-19 m (assumed bot height).
- Turn calibration: 351.5 deg per measured turn (97.6 %), FOV 103 at 1920 px consistent.
- Not measured: eye height (the eye-height clip is a normal run), head hitbox, HP. The user said the reconstruction need not be exact, so these stay assumptions from the profile.

## Behaviour spec (step 4, decided 2026-10-22)

The user asked for a close reconstruction, not an exact one. Values marked (m) are measured from the clips of 2026-10-22, (a) are assumptions, all `verified: false` in the profile.

- **Round:** a Range round is one AimTune round (`ROUND_SECONDS`, 45 s) of continuous Eliminate: bots keep coming until the time is up; the round's REMAINING-style counter shows kills. A full Eliminate 100 run is not a round (it would take about 3 minutes and give the optimizer one value); a later "Range challenge" menu entry can play 100 bots for fun without feeding the finders.
- **Bots alive:** one at a time (m: one visible in idle and almost always in the run).
- **Next spawn:** 0.4 s after a kill, spread 0.2-0.8 s (m: spawn-to-spawn 1.8 s median minus shot lifetime 1.4 s).
- **Lifetime when not killed:** none by default (m: an unshot bot stayed 44 s with no Speed selected). Speed presets for later: Easy 3.0 s, Medium 2.0 s, Hard 1.2 s (a, not measured; a bot that despawns counts as a miss).
- **Position:** bearing uniform -50 to +80 deg from the counter's forward direction (m: -51 to 81), distance 5-20 m, log-uniform (m, rough: median 6.8, 3-19), standing on the floor (eye height 1.6 m, a).
- **Strafe (Bots = Strafe):** move-stop-move: moving segments 0.25-0.45 s (m: median 0.36), stops 0.3-0.7 s (m: 0.2, lengthened after the user's first play: the bots moved too much and turned too fast), direction reverses with probability 0.6 after each stop, otherwise continues; speed 2.0 m/s (m: about 12 deg/s at about 12 m, lowered with the stops and again from 2.2 after the user's second play, 2026-10-24: a touch too fast; a: the distance), strafe stays within +-2 m of the spawn point. "Reset" bots stand still (m).
- **Hitboxes and kill rule:** humanoid of 1.8 m (a), head sphere about 0.25 m wide (profile `clickTargetDiameterDegrees` decides the angular size per distance as today, a). Bot Armor on: a head hit kills; body hits kill after 4 (a: Vandal against 100 HP + 50 armor). Armor off: 3 body hits (a).
- **Metrics per bot:** time to kill (spawn to kill), first-reach error against the target position at the end of the reach (M15a, done), first-shot head accuracy, shots per kill; per round: kills and mean time to kill. Score metrics: ACCURACY (first-shot head hits) and TIME_TO_HIT (time to kill) as for flicks, with a `range` scenario baseline.
- **Curve finder:** Range rounds go into the score-based searches (strength search, A/B). Their reaches enter the error analysis only when the bot was stopped during the whole first reach (M15a filter, done: `movementSamples` drops every reach whose target moved; ALGORITHMS "Moving targets").

## M15c Range scenario (step 5, done 2026-10-23)

`RangeScenario` plays the behaviour spec above; details, metrics, the aimer and the gate numbers are in docs/ALGORITHMS.md "Range mode (M15c)". What is measured and what is assumed, per value, is in the `trainer.range.valueNotes` of `games/valorant.json` (all `verified: false`):

- measured (rough, 60 fps clips): spawn delay about 0.4 s (spread assumed), bearing -51..+81, distance 3-19 m (partly circular, the bot height was assumed), strafe segments and stops, no despawn of an unshot bot;
- assumed: speed presets Easy 3.0 / Medium 2.0 / Hard 1.2 s, bot height 1.8 m, head hit sphere 0.25 m, kill rule (head kills, 4 body hits with armor, 3 without), strafe reverse probability and offset limit, uniform bearing and log-uniform distance;
- deviation from the spec: the head is a real-size sphere at its distance, not a click-target diameter drawn independently of the distance;
- the settings offer speed (none, Easy, Medium, Hard) and Bots (Strafe, Stand still); armor stays on;
- the simulated aimer is far slower against strafing bots than the real clips (4-7 kills per 45 s round, about 7 s per kill, against about 1.4 s in the clips), so the gate numbers say little about people.

## M15d Range mode as a standard round (done 2026-10-23)

- Default: for a game with a `trainer.range` block the default scenarios are flick, range and flash (flash by its importance setting). Fresh settings, "Reset to defaults" and onboarding use it; saved settings keep their scenarios, and the settings tile still toggles Range. Games without the block keep flick and flash.
- "Range" start variant of the SENS module on the dashboard (until M24 the menu entry "Range mode"; shown for games with a Range block, M switches it): a blind sensitivity test of range and flick in equal shares, independent of the saved toggles, without flash and tracking; a line under the Blind/Range switch shows the speed and bots settings in use. Rounds are stored and merged like every other blind round.
- Curve finder: excluded on purpose. Range rounds in the strength search raised the false directions for flat players from 9/16 to 14/16 (M15c gate, unchanged with the slower strafe); `AccelRun` and `RefineEngine` do not list `RANGE`, a test guards it.
- Gate with the slower strafe: default flat player still 0/30 false offers (ALGORITHMS "Range mode (M15d)").
- Still assumed or open: armor setting not exposed (armor stays on), the simulated aimer is not calibrated against real play, head hitbox and eye height are unmeasured; all `trainer.range` values stay `verified: false`.
