# Research export

History, then "Share data for research" writes all stored rounds into one file,
`aimtune-research.json.gz`, in `%APPDATA%\AimTune\exports\research\`. A new export replaces the old file (it is
written to `aimtune-research.json.gz.partial` first and then moved over it). AimTune never uploads it; the person sends it themselves. It exists because the method has never been run on other
people's recordings (docs/history/ASSESSMENT.md D4): the files let someone check the first-reach definition, the
low-speed pattern of the acceleration analysis, and whether the score curve has an interior optimum for people.

Size: about 70 KB compressed per 45-second round at a 1 kHz mouse (28 rounds: 2 MB).

## Contents

```
{ "format": "aimtune-research", "formatVersion": 2, "appVersion": "0.1.0", "metricsVersion": 3, "notes": "...",
  "sessions": [ { "index": 0, "kind": "sensitivity" | "accel_ab" | "accel_search", "gameId": "valorant", "dpi": 800.0, "dayIndex": 0,
      "rounds": [ { "scenario": "flick", "cm360": 40.0, "dpi": 800.0, "blind": true, "accel": null,
          "startOffsetSeconds": 5.0, "durationNanos": 45000000000,
          "mouse": { "dtNanos": [...], "dx": [...], "dy": [...] },
          "targetEvents": [ {"type":"spawn","tNanos":0,"id":0,"x":5.0,"y":1.0,"z":-9.0,"radius":0.25},
                            {"type":"waypoint","tNanos":..,"id":..,"x":..,"y":..,"z":..},
                            {"type":"miss","tNanos":..}, {"type":"hit","tNanos":..,"id":..} ],
          "metrics": { "accuracy": ..., ... } } ] } ] }
```

* Times are nanoseconds from the start of the round. `dtNanos` is the time since the previous mouse report
  (the first since the round start); `dx`, `dy` are raw counts. Degrees per count follow from `cm360` and `dpi`:
  `360 / (cm360 / 2.54 * dpi)`.
* Target positions are metres in the camera frame at the start of the round (x right, y up, -z forward). The view
  starts at yaw 0, pitch 0 and moves by the mouse counts; a shot hits if the ray along the view meets the sphere.
* `accel` is not null when a simulated Raw Accel curve was applied to the counts in the trainer.
* `kind` tells a sensitivity test from an acceleration calibration/A-B run; `blind` and `accel` tell plain from curve rounds.

## What it does not contain

No name, computer or user name, file path, clock time or date (sessions get a `dayIndex` counted from the
first session, rounds a `startOffsetSeconds` within their session), and nothing from other programs.
DPI, sensitivity and the game id are included on purpose. `ResearchExport` writes as a stream and never
builds the whole file in memory.

Format 2 (2026-11-24) removed two leaks of format 1:

* `seed` per round. Seeds come from `System.nanoTime()` (QPC on Windows), so a seed showed the uptime of the PC
  when the round started. The target events hold everything the seed generated.
* The date and time in the file name. The name is now fixed.

The gzip header holds no time (MTIME 0) and no file name (`ResearchExportTest` checks both).

The data is not anonymous. Mouse movements are personal data of a kind that can be linked to a person. The
texts in the app never call the file anonymous.

## Contributions on GitHub (temporary, off)

A second way to share the file, behind a build switch (ADR 0007). It is off in every build until the legal
questions for a public collection are settled (data protection, the maintainer's contact duties). The local
export works without it.

When it is on, Share data shows "Contribute training data". `ContributeScreen` explains why the data helps,
what the file contains and what it does not, that sharing is voluntary, and that a GitHub issue is public,
shows the contributor's account, and that copies others made can stay after a deletion. Every step needs a click:

1. "Create file" writes the export with `ResearchExport` (the same file as above).
2. "Show file" opens Explorer with the file selected (`explorer.exe /select,`).
3. "Open issue on GitHub" opens the default browser on
   `https://github.com/<repository>/issues/new?template=<template>&title=...&summary=...`. The link holds the app
   version, the format version and the counts of sessions and rounds and the file size, all URL-encoded. It never
   holds measurements or paths. A link longer than 6000 characters drops the summary; if it is still too long, no
   link is offered. If the browser does not open, "Copy link" puts the link on the clipboard.

The contributor attaches the file to the issue (GitHub accepts `.gz` up to 25 MB) or links a copy in their own
account. AimTune sends nothing, has no token or login and makes no HTTP request for this. It keeps no record of
who contributed what.

Configuration (`gradle.properties`, copied into `contribution.properties` by `processResources`):

| Property | Default | Meaning |
|---|---|---|
| `aimtune.contribution.enabled` | `false` | Only `true` turns the feature on |
| `aimtune.contribution.repository` | `veqzo/Aim-Tuner` | `owner/name`, checked against GitHub's naming rules |
| `aimtune.contribution.template` | `training-data.yml` | The issue form file name |

An invalid repository or template keeps the feature off.

### Turn it on

1. Settle the legal questions first (docs/PROGRESS.md, open questions).
2. Move `docs/contribution/training-data.yml` to `.github/ISSUE_TEMPLATE/` on `main` (GitHub reads issue forms
   only from the default branch) and create the label `training-data`.
3. Set `aimtune.contribution.enabled=true` (or pass `-Paimtune.contribution.enabled=true` to the release build).

### Turn it off or remove it

* Off: set `aimtune.contribution.enabled=false` and remove the issue form from `.github/ISSUE_TEMPLATE/`.
* Remove: delete `app/src/main/kotlin/dev/veqzo/aimtune/app/contribute/` and its tests, the button in
  `ShareDataScreen`, `contributions`, `showContribute` and `revealFile` in `AppContext`, the `contribute.*` keys
  in both bundles (and `contribute` in the `BundlesTest` key pattern), `contribution.properties`, the three
  `aimtune.contribution.*` properties and the `contribution` lines in `processResources`, the two tour steps,
  and `docs/contribution/`.
