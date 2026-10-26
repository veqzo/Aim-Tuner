# Tester guide: AimTune

## 1. Install

1. Download `AimTune-<version>-windows.zip` from the latest release on GitHub and unzip it anywhere.
2. Start `AimTune\AimTune.exe`.
3. If Windows SmartScreen warns you, choose **More info**, then **Run anyway**. The app is not code-signed yet.

## 2. Set up once

On first launch, a four-step setup asks for:

- your mouse **DPI**
- your **game** (Valorant) and its **current sensitivity**
- your **aim style** (wrist / mixed / arm)
- your **mousepad size**

Before you test, turn off **Enhance pointer precision** in Windows (Settings > Mouse > Additional mouse settings > Pointer Options). If you use a Raw Accel curve, disable it too. Otherwise the measurements are distorted.

## 3. Run a test

1. Click **Start a test** in the main menu.
2. Each round lasts **45 seconds**. Click the targets as fast and accurately as you can.
3. After each round you see how many targets you hit and how sure the program is. The sensitivity of each round is **never shown on purpose**, so do not try to judge whether a round felt good or bad.
4. Play **15 to 30 rounds**, ideally on **two different days**. Use **Stop for today** at any time.

Controls: click starts a round, `Esc` aborts it, `F11` toggles fullscreen.

Raw Accel is not part of this test. Section 4 explains the optional curve search.

## 4. Optional: find a Raw Accel curve

Only do this part if you already use Raw Accel or you want to check whether a curve helps you. Most players will be told that no curve is clearly better, and that is a normal result.

You need a mouse with a report rate of **1000 Hz or higher**. Keep Raw Accel disabled in Windows: AimTune simulates the curves itself and does not change your game or the Raw Accel driver.

1. Finish section 3 first.
2. In the main menu, open **Raw Accel** (key **A**), then choose **Find a curve**.
3. Play the blind rounds as before. You do not see which curve is tested.
4. At the end, AimTune runs a blind A/B test of the curve against your normal sensitivity.
5. A Raw Accel `settings.json` is exported **only** if the result says the curve is clearly better. Otherwise the answer is "no curve".
6. If you already have a Raw Accel `settings.json`, choose **Refine my curve** and open the file with **Open settings.json...**. Refine only tries small changes, and it rarely finds one worth exporting.

**Report:** whether you got a curve or "no curve", the exact text on the Results screen, and your current Raw Accel curve values if you use one.

## 5. Read the result

The Results screen shows:

- the **recommended sensitivity**, both as your game's value and as cm/360°
- a **range** of sensitivities that scored about the same
- a **confidence** value

Enter the recommended value in your game and play with it for a few days before you judge it.

## 6. Share your data

1. Open **History** in AimTune.
2. Click **Share data for research**. This writes one file named `aimtune-research-<date>.json.gz` to `%APPDATA%\AimTune\exports\research\`. Paste that path into the Explorer address bar to open the folder directly.
3. Send that file to the developer. It contains no name, file paths or clock times, only your rounds, DPI and sensitivity. Nothing is uploaded automatically.
4. Please also send a short note with:
   - your results (recommended sensitivity, cm/360°, confidence)
   - anything that felt unclear, confusing or wrong
   - your language setting, and whether you played fullscreen or windowed
