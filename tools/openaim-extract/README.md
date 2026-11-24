# openaim-extract

Dev-only tool. It reads a local checkout of [open-aim](https://github.com/unkwsr/open-aim)
and writes aggregated Valorant engagement statistics to
`games/src/main/resources/games/valorant-engagements.json`. It is not part of the app build.

## Why

AimTune's scenarios must match the engagements a Valorant player meets: distance, angle off
the crosshair, bot speed across the screen, timing. open-aim holds 18 curated drills and
about 126 community drills on Valorant maps. Together they are a large, free sample of how
players set up engagements.

## Rules

- Read only. The tool never writes into the open-aim folder.
- Only aggregated numbers enter this repo: counts, means, minimum and maximum, quantiles (p5-p95), bootstrap intervals.
  No scene text, names, coordinates, map layouts, code or assets from open-aim.
- open-aim's MIT license covers only its wrapper. The maps are Riot assets. We use none of them.

## Run

```
cd tools/openaim-extract
python extract.py <path to open-aim>            # writes the resource file
python extract.py <path to open-aim> --out x.json
python -m unittest -v test_extract
```

Standard library only (Python 3.10+).

## What it measures

Each map scene gives a set of exposure points: the places where the player sees a bot.

| Source in the scene | Exposure point | Bot motion |
|---|---|---|
| `fixed` / `area` spawner | spawner position | standing, or strafing if the actor has `strafe` |
| `cross` behaviour | every waypoint after the first (the first is the hidden start) | the leg speed into that waypoint |
| `path` behaviour | every waypoint after the first | the leg speed into that waypoint |

Per point: distance (m), absolute yaw offset from the spawn yaw (deg), elevation (deg), head
angular size (deg, head Ø 0.24 m), and angular speed across the line of sight (deg/s, moving
bots only). Timings come from the bot settings: reaction, flash wait, spawn and dispatch
intervals, strafe dwell and travel.

Groups: `all`, by content (`flash`: a bot has flash stops; `peek`: bots cross, strafe or
walk a path; `static`: bots only stand), and by source (`curated`, `community`).

## Assumptions and limits

- Frame facts come from the open-aim engine bundle: metres, y up, positions at the feet,
  forward `(-sin yaw, 0, -cos yaw)`, eye 1.75 m. Bot head centres are taken at 1.75 m too.
- There is no map geometry, so the tool cannot test line of sight. Points behind cover
  count as seen. Waypoint 0 of a route is assumed hidden.
- Offsets are measured from the spawn. In drills where the player walks a route this
  overstates distance and yaw offset; the `flash` and `curated` groups are cleaner.
- Arena drills (no map) are dropped.
- Weighting: each scene has weight 1, split equally over its points, so a scene with 60
  bots does not outweigh 10 small scenes. The median CI resamples whole scenes.
- Community drills show what players choose to train, not how often a situation occurs
  in matches. The output carries `"verified": false` for that reason.
