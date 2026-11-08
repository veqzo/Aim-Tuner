# Valorant weights (M23 step b)

Status: **confirmed by the user: option A** (duel-win metric), with TRACKING 0, the mix, the flick bins, the head sizes and offLevelShare 0.27. Step c added `DuelScorer` in `core` and replayed it (section 8); the app does not use it yet (step g).

This document derives the score weights, the scenario mix and the target geometry for Valorant.
Every number has a source: the open-aim extract (`games/src/main/resources/games/valorant-engagements.json`,
110 scenes), a cited Valorant fact, or the user's own rounds. Assumptions are marked as such.

## 1. Result

| Item | Now | Proposal | Main reason |
|---|---|---|---|
| Score | z-scores, ACCURACY 40, TIME_TO_HIT 30, TRACKING 20, FLASH_DODGE by importance | one **duel-win** metric per engagement (section 4); TRACKING 0 for Valorant | the current weights price a first-shot miss at 990–1340 ms; a Valorant duel prices it at 160–350 ms |
| Scenario mix (finder and curve) | round-robin over the chosen list, default flick, flash (+ range) | flick 4 : strafe 1 : flash 1 (67 % / 16 % / 16 %) | open-aim exposure shares (section 5) |
| Head size | 0.4–1.9° (median 0.90° in the user's rounds) | head Ø 0.24 m at sampled distances: median 0.75°, p10 0.32°, p90 1.23° | open-aim head sphere, flash-group distances (section 6) |
| Flick angle bins | ≤5: 0.30, ≤10: 0.30, ≤20: 0.20, ≤40: 0.15, ≤90: 0.05 | ≤5: 0.18, ≤10: 0.21, ≤20: 0.24, ≤40: 0.18, ≤90: 0.19 | flash group, the cleanest "holding an angle" data (section 6) |
| Off-level targets | spread 1.5°, offLevelShare 0.12 | offLevelShare 0.27 (share with \|elevation\| > 1.5°) | flash group (section 6) |

The key finding: **the current weights do not cause the 1.5x speed bias.** They push toward slow,
not fast. In the user's rounds, neither accuracy nor time to hit changes with the sensitivity
(R² ≤ 0.13, section 3). But section 3 and the replay (section 8) show that this data cannot tell: almost
all rounds were played at one sensitivity. A flat fit then says nothing. Noise and the search prior may
decide the result, but the data cannot prove it. The main levers are a realistic task (smaller heads, moving
bots, steps e–g) and a score that prices a miss like Valorant does.

## 2. Valorant facts used

| Fact | Value | Source |
|---|---|---|
| Vandal damage, 0–50 m | head 160, body 40, legs 34 | [Liquipedia Vandal](https://liquipedia.net/valorant/Vandal), [Riot Data Drop](https://playvalorant.com/en-gb/news/dev/valorant-data-drop-phantom-vs-vandal/) ("160 damage at any range") |
| Phantom damage | head 156 (0–20 m) / 140 (20 m+), body 39 / 35 | [Liquipedia Phantom](https://liquipedia.net/valorant/Phantom) |
| Vandal fire rate | 9.75 rounds/s (one shot each 103 ms) | [Valorant wiki Vandal](https://valorant.fandom.com/wiki/Vandal) |
| HP and armor | 100 HP; light armor 25, heavy armor 50, regen shield 25 | [Valorant wiki Armor and Shield](https://valorant.fandom.com/wiki/Armor_and_Shield) |
| Vandal kill on 150 HP | 1 head shot (0 s), or 4 body shots (0.308 s) | Valorant wiki Vandal, TTK table |
| Vandal spread | first shot 0.25°; walking +3°, running +6° | Valorant wiki Vandal |
| Run speed | 5.4 m/s | Valorant wiki Vandal |
| Peeker's advantage | 40–70 ms on average | [Riot, "On Peeker's Advantage & Ranked"](https://playvalorant.com/en-us/news/game-updates/04-on-peeker-s-advantage-ranked) |
| Human reaction time | about 247 ms | same Riot post |
| Engagement distance in matches | "nearly half of all Phantom kills took place within 15m" | Riot Data Drop |
| Bot reaction in open-aim drills | median 0.40 s (CI 0.40–0.425), n = 65 scenes | open-aim extract, `timings.bot_reaction_s` |

## 3. The user's rounds (read-only copy of the database)

Rounds of the current database, all curves included. σ is the spread over rounds.

Correction (step c): the curve rounds (accel_ab, accel_search, accel_refine) all use one sensitivity,
48.7 cm/360. Only 17 rounds are blind sensitivity rounds, and only the 6 flick rounds span 38–70 cm/360.
So the R² columns measure noise at almost one point. They do **not** show that the metrics are flat
over the sensitivity. The σ columns stay valid.

| Scenario | Rounds | σ first-shot acc. | σ time to hit | R² of acc. vs ln(cm/360) | R² of time vs ln(cm/360) |
|---|---|---|---|---|---|
| flick | 22 | 0.056 | 44 ms | 0.03 | 0.01 |
| micro | 10 | 0.035 | 19 ms | 0.02 | 0.01 |
| switching | 13 | 0.046 | 20 ms | 0.07 | 0.00 |
| flash | 23 | 0.099 | 62 ms | 0.01 | 0.13 |

Per target (all flick rounds): a target hit with the first shot takes a median 609 ms (σ 145 ms).
A target with a missed first shot takes a median 903 ms. **The trainer already charges a miss
about 293 ms inside TIME_TO_HIT** (micro 263 ms, flash 265 ms).

Binomial noise of first-shot accuracy at about 76 shots per round is √(0.78·0.22/76) = 0.048. That
is most of the observed σ 0.056: accuracy per round is nearly pure noise at the current target size.

## 4. Score: price of a miss

### 4.1 What a miss costs in Valorant

A Valorant rifle duel is won by the player who kills first. With a Vandal, a head shot kills at
once. After a missed head shot, the shortest kill on 150 HP is 4 body shots: 3 more shot intervals,
0.308 s (4 shots after the miss: 0.411 s). So a miss delays the kill by Δ = 308–411 ms.

Duel model: the opponent kills at a random time with the user's own spread (an opponent of equal
skill, σₒ = 145 ms from the per-target data). Peeker's advantage (40–70 ms) helps the peeker and
hurts the holder; over both roles the net shift is 0, so the model uses no shift.

P(win) = p · G(T) + (1 − p) · G(T + Δ), with G the chance that the opponent is not yet done.

The exchange rate between first-shot accuracy p and time T at the mean is the "price of a miss":

| σₒ | Δ | p | price of a miss |
|---|---|---|---|
| 100 ms | 308 ms | 0.78 | 160 ms |
| 145 ms | 308 ms | 0.78 | 219 ms |
| 145 ms | 411 ms | 0.65 | 276 ms |
| 200 ms | 411 ms | 0.65 | 348 ms |

Over all tested assumptions the price is **160–350 ms**. It is lower than Δ because once the user
is very late, more delay changes little.

### 4.2 What the current score charges

With z-scores, the score is linear near the mean. The implied price of a miss is
(w_acc / σ_acc) / (w_time / σ_time) plus the miss cost already inside TIME_TO_HIT:

| Scenario | from weights 40/30 | inside time to hit | total |
|---|---|---|---|
| flick | 1047 ms | 293 ms | **1340 ms** |
| micro | 724 ms | 263 ms | **987 ms** |
| flash | 835 ms | 265 ms | **1100 ms** |

The current score charges a miss 3–8 times more than a Valorant duel does. That favours slow,
careful aim. It does not explain a fast result.

### 4.3 Proposal: duel-win metric

Replace ACCURACY and TIME_TO_HIT in the optimizer score by one metric per round: the mean duel-win
chance over its engagements.

- Engagement with a first-shot hit at time t: win = G(t).
- Engagement with a first-shot miss at time t₀: win = G(t₀ + Δ), Δ = 308 ms (the conservative end: least penalty for a miss, so the least push toward slow).
- Target that leaves unshot: win = 0.
- Flash engagement not dodged (the player looked at the flash): win = 0. The flash blinds for 1.5 s in the profile, far longer than the 0.40 s bot reaction, so a blinded duel is lost. This replaces the separate FLASH_DODGE weight; the importance setting still scales flash rounds (section 5).
- G(t) = 1 − Φ((t − μₒ) / σₒ). μₒ and σₒ are the median and spread of the user's first-shot-hit times in that scenario, over all their rounds. So the opponent is always "as good as you", and the metric needs no hand-set constant.

Why one metric and not new weights: fixed weights only fit one σ ratio, and the σ values change per
user and per scenario (table in section 3). The duel metric needs no σ ratio and stays in the units
that decide a Valorant fight. ACCURACY and TIME_TO_HIT stay as diagnostics.

Fallback if you prefer to keep weights: ACCURACY 5, TIME_TO_HIT 95 reproduces a miss price near
290 ms for flick (it is almost all already inside TIME_TO_HIT). I do not recommend it: it fits only
the flick σ values of this database.

**TRACKING: 0 for Valorant.** A moving shooter is inaccurate (walk +3°, run +6° against 0.25° first
shot), so Valorant fights are click fights on stopped or slow targets. Moving targets enter through
the strafe click scenarios, not through tracking. The user disabled tracking already.

## 5. Scenario mix

Scene kinds in open-aim (share of 110 scenes, bootstrap 95 % CI):

| Kind | Share | CI |
|---|---|---|
| static (bots only stand) | 56 % | 46–66 % |
| peek (bots cross, strafe or walk) | 27 % | 19–36 % |
| flash (bots with flash stops) | 16 % | 10–24 % |

Inside peek scenes, 60 % of exposures move. That gives three engagement types:

| Engagement | Share | AimTune scenario |
|---|---|---|
| standing bot | 56 % + 27 % · 0.40 = 67 % | FLICK (with the angle bins of section 6) |
| moving bot | 27 % · 0.60 = 16 % | SWITCH_STRAFE or MICRO_STRAFE |
| flash | 16 % | FLASH |

Proposal: rounds in the ratio **flick 4 : strafe 1 : flash 1**, the same for the sensitivity finder
and the curve finder. MICRO and SWITCHING stay available but leave the Valorant default, because
FLICK with the new ≤5° and ≤10° bins covers small corrections. RANGE stays optional and unchanged.
FlashImportance keeps its meaning: it scales the flash share (NEVER 0, SOMETIMES 1, OFTEN 2 parts).

Limit: open-aim drills show what players train, not how often a situation occurs in a match.
There is no public match data on engagement types. The CI above shows the sampling error only.

## 6. Target geometry

Head Ø 0.24 m (open-aim head sphere r 0.12 m). Angular size = 2·atan(0.12 / distance).

| Group | Distance p50 | Head p10 | Head p50 | Head p90 |
|---|---|---|---|---|
| all scenes | 31.0 m | 0.25° | 0.44° | 1.01° |
| flash | 18.3 m | 0.32° | 0.75° | 1.23° |
| user's current rounds | – | 0.47° | 0.90° | 1.64° |

Distance shares (0–15 / 15–30 / 30+ m): all scenes 13 / 34 / 53 %, flash 33 / 45 / 23 %. Riot
reports that nearly half of Phantom kills happen within 15 m. Open-aim drills are set longer than
matches; the flash group is closest to the Riot data. Proposal: **sample the distance from the flash
group** and compute the head size from it. Then the median head is 0.75° instead of 0.90°.

Offset from the crosshair (angle between the spawn yaw and the bot, weighted per scene):

| Bin | Now | all scenes | flash | Proposal |
|---|---|---|---|---|
| ≤5° | 0.30 | 0.07 | 0.18 | 0.18 |
| 5–10° | 0.30 | 0.12 | 0.21 | 0.21 |
| 10–20° | 0.20 | 0.16 | 0.24 | 0.24 |
| 20–40° | 0.15 | 0.25 | 0.17 | 0.18 |
| 40–90° | 0.05 | 0.34 | 0.16 | 0.19 (incl. 0.05 above 90°) |

The all-scenes values overstate the angle: in static drills the player turns between targets, and
the tool cannot know the order. In flash drills the player holds one angle, so the offset is the
real flick from the crosshair. Proposal: use the flash bins. The current bins put 60 % below 10°;
the data put 39 % there.

Elevation: the share with |elevation| > 1.5° is 27 % (CI 13–42 %) in the flash group, 46 % in all
scenes. Proposal: offLevelShare 0.27.

Moving bots: median angular speed 10.4°/s (CI 7.8–12.5), p90 22.7°/s. Strafe dwell 0.33 s, travel
1.5 s. These feed step g.

## 7. Limits

- No match data on engagement types; the open-aim drills are a proxy (`"verified": false`).
- The opponent model assumes an equal opponent with a normal kill time. The price of a miss stays in 160–350 ms over the tested range.
- The user's database is small (22 flick rounds) and mostly played with a curve at one sensitivity (48.7 cm/360). It cannot show how the metrics depend on the sensitivity.
- Weapon: Vandal. With a Phantom past 20 m a head shot does 140 damage and no longer kills 150 HP; that raises the value of a hit, not of speed. The model stays with the Vandal, the rifle with the one-shot head kill at all ranges.

## 8. Replay of the old rounds (step c)

`storage/.../review/RealDataDuelReplayTest.kt` (runs only with `AIMTUNE_REAL_DB` set to a copy of the
database). Current value 48.7 cm/360, MIXED, search range 30.0–78.0 cm/360, optimizer seed 0.
Valorant sensitivity at 800 DPI = 16.33 / cm360.

| Variant | Rounds used | Optimum | Valorant @800 | Equivalent stretch | Confidence |
|---|---|---|---|---|---|
| Old weights (40/30/20/15), all blind rounds | 11 | 38.3 cm | 0.426 | 36.0–40.6 cm | 44 % |
| Old weights, click rounds | 6 | 37.2 cm | 0.438 | 34.1–40.0 cm | 23 % |
| Duel score, click rounds | 6 | 37.4 cm | 0.437 | 34.6–40.0 cm | 31 % |

- The old scheme reproduces the app's result (0.43 against the reported 0.45). The user plays 0.3 (54.4 cm).
- The duel score moves the optimum by less than 1 %. Only the 6 flick rounds have a baseline: the other click scenarios have one round each, and a baseline needs 3 rounds.
- The duel scores of the flick rounds are flatter (31.6–48.3) than the old scores (22.8–88.9): the duel score does not reward noise in accuracy so much.
- Conclusion: the score alone does not remove the bias in this data. The data is too thin to show what does: 6 rounds at 6 sensitivities. The fix must come from the realistic task (steps e–g), and the acceptance test is step i with the friend's data.
- An earlier draft of `DuelScorer` built a baseline from a single round. That scored the round against itself and pushed the optimum to the range edge (30 cm, 0.54). It now needs 3 rounds per scenario, as `ScoreCalculator` does.

## 9. Next steps

- e–g: geometry and mix from this document.
