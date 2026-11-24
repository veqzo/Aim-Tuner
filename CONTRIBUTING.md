# Contributing

## Workflow

- Read [docs/PROGRESS.md](docs/PROGRESS.md) first and keep it updated.
- Work on the `dev` branch and open pull requests into `dev`; `main` receives merges from `dev` for features and releases, and a tag `vX.Y.Z` on `main` publishes a release.
- One logical change per commit, format `<type>: <description>` (feat, fix, refactor, docs, test, chore, perf, ci).
- Logic in `core` needs tests. Run `./gradlew build` before opening a PR.
- Respect the anti-cheat rules in [CLAUDE.md](CLAUDE.md): no game process access, no input outside the app window, no custom drivers.

## How to add a new game

Games are JSON profiles and need no code changes. Bundled profiles live in `games/src/main/resources/games/` and are listed in `index.json`; users can drop their own files in `<data dir>/games/*.json` (picked up on start).

```json
{
  "id": "cs2",
  "name": "Counter-Strike 2",
  "yaw": 0.022,
  "fov": { "degrees": 90.0, "type": "horizontal", "aspect": { "width": 4, "height": 3 }, "scaling": "hor_plus" },
  "sensitivity": { "decimals": 3 },
  "sources": ["https://..."],
  "notes": "Anything a reviewer should know."
}
```

- `yaw`: degrees per count at sensitivity 1.
- `fov`: the value the game shows, whether it is `horizontal` or `vertical`, and the aspect ratio it refers to. `scaling` is `hor_plus` (vertical angle kept when the window gets wider).
- `sensitivity.decimals`: how many decimals the game accepts.
- `fov.trainerAspect` (optional, default 16:9): the shape of the picture the trainer draws; the window is letterboxed to it, so the horizontal FOV is exactly what the game shows at that shape.
- `trainer` (optional block): what the targets look like. Every field falls back to the defaults of `TrainerGeometry`, and `verified` stays `false` until the values were measured in the game (the app tells the user when they are estimates).

```json
"trainer": {
  "clickTargetDiameterDegrees": { "min": 0.4, "max": 1.9 },
  "clickTargetDistanceMeters": { "min": 6.0, "max": 30.0 },
  "flickAngles": [{ "maxDegrees": 5, "weight": 0.3 }, { "maxDegrees": 10, "weight": 0.3 }, { "maxDegrees": 20, "weight": 0.2 }, { "maxDegrees": 40, "weight": 0.15 }, { "maxDegrees": 90, "weight": 0.05 }],
  "microStepDegrees": { "min": 1.5, "max": 8.0 },
  "switchingSeparationDegrees": 10.0,
  "trackingDiameterDegrees": { "min": 2.0, "max": 3.7 },
  "trackingDistanceMeters": 14.0,
  "strafeSpeedMetersPerSecond": { "min": 3.0, "max": 6.0 },
  "stopProbability": 0.25,
  "flash": { "popDelaySeconds": { "min": 0.45, "max": 0.9 }, "blindSeconds": 1.5, "exitMarginDegrees": 10.0, "verified": false },
  "verified": false
}
```

Heights and map: `eyeHeightMeters` and `headHeightMeters` give the camera height and the height of the middle of an enemy's head above the floor (they only tilt level targets slightly with distance). The `elevation` block says where targets appear vertically: `spreadDegrees` (standard deviation around head level for most targets), `offLevelShare` (share on another level), `offLevelDegrees` (how far off level, `min`/`max`) and `loweredShare` (share of those below the player); `"uniform": true` restores the old fully 3D field and exists only to compare results. The `arena` block describes the map the scene draws and where targets may stand at it: `wallDistanceMeters`, `featureShare` (share of flick and switching targets moved to a corner or doorway), `snapDegrees` (how far in yaw a target may be moved) and `features` (`kind` `corner` or `doorway`, `yawDegrees`, `distanceMeters`). All three are optional with the defaults of `TrainerGeometry`; mark values as assumptions in the profile's notes until they were measured in the game.

Player body (optional, M23): the `body` block gives the size and speed of a player of the game: `headDiameterMeters`, `capsuleHeightMeters`, `eyeStandMeters`, `eyeCrouchMeters`, `runSpeedMetersPerSecond`, `walkSpeedMetersPerSecond` and `crouchSpeedMetersPerSecond`. Every field is required, and `valueNotes` needs an entry with `verified` and a `source` for each of them, as in the `range` block. Set `eyeHeightMeters` and `headHeightMeters` to `eyeStandMeters`, so that a bot stands like a player.

Ground movement (optional, M23): the `movement` block gives how a player of the game accelerates and stops: `accel`, `friction`, `brakingFriction`, `frictionMin`, `aboveMaxSpeedDecay` and the gait factors `walkAccelFactor`, `walkBrakingFactor`, `crouchAccelFactor`, `crouchFrictionFactor`, `crouchFrictionMinFactor`. It needs a `body` block for the top speeds. Every field is required, with a `valueNotes` entry for each, as in the `body` block.

The `flash` block (also optional, same fallback) describes the flash turn-away scenario: how long a flash is visible before it pops, where it appears (`cueOffsetDegrees`, `cueElevationDegrees`, `distanceMeters`), the pause between flashes (`gapSeconds`), how long a player who looks at it is blind, where the enemy shows up afterwards (`returnSpreadDegrees`, `returnMinDegrees`) and how far past the edge of the screen the flash must be (`exitMarginDegrees`). The half field of view comes from `fov`. `sources` and `notes` may list where the values come from; mark them `verified` only when measured.

Sizes are angles (degrees at the game's FOV), never meters or pixels; the distance only gives the scene depth. `flickAngles` is the distance of the next target from the previous one (where the crosshair is after a hit): buckets by upper limit and weight. How to measure a head in the game's shooting range is described in [docs/history/REVIEW.md](docs/history/REVIEW.md) ("Realism").

Verify yaw and FOV against a current, reliable source and cite it in `sources` and the PR. Never guess values.

## How to add a language

1. Copy `app/src/main/resources/assets/i18n/messages.properties` to `messages_<code>.properties` and translate the values (UTF-8, keep the `{0}` placeholders, no leading or trailing spaces).
2. Add the language to `Language` in `app/i18n/I18n.kt` (code, name, locale). `BundlesTest` fails until every key is translated.
3. Check the screens with `./gradlew :app:screenshots` after pointing `--language` at the new code.
