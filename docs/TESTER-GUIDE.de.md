# Tester-Guide: AimTune

## 1. Installieren

1. Die neueste `AimTune-<version>-windows.zip` aus den GitHub-Releases herunterladen und irgendwo entpacken.
2. `AimTune\AimTune.exe` starten.
3. Warnt Windows (SmartScreen): **Weitere Informationen** → **Trotzdem ausführen**. Das Programm ist noch nicht signiert.

## 2. Einmalig einrichten

Beim ersten Start werden vier Schritte abgefragt:

- **DPI** deiner Maus
- **Spiel** (Valorant) und deine **aktuelle Sensitivity** im Spiel
- **Aim-Stil** (wrist / mixed / arm)
- **Mauspad-Größe**

Vorher in Windows **„Mauszeigerbeschleunigung verbessern“ ausschalten** (Einstellungen > Maus > Zusätzliche Mausoptionen > Zeigeroptionen). Falls du eine Raw Accel-Kurve nutzt, diese ebenfalls deaktivieren. Sonst werden die Messungen verfälscht.

## 3. Testen

1. Im Hauptmenü **Start a test** (Test starten) anklicken.
2. Eine Runde dauert **45 Sekunden**. Klicke die Ziele so schnell und genau wie möglich.
3. Nach jeder Runde siehst du, wie viele Ziele du getroffen hast und wie sicher sich das Programm ist. Die Sensitivity der Runde wird **absichtlich nicht angezeigt**. Also nicht darüber nachdenken, ob sie sich gut oder schlecht angefühlt hat.
4. Spiele **15 bis 30 Runden**, möglichst an **zwei verschiedenen Tagen**. Mit **Stop for today** kannst du jederzeit aufhören.

Steuerung: Klick startet eine Runde, `Esc` bricht ab, `F11` schaltet Vollbild um.

Raw Accel ist nicht Teil dieses Tests. Abschnitt 4 erklärt die optionale Kurvensuche.

## 4. Optional: Raw-Accel-Kurve suchen

Mach diesen Teil nur, wenn du Raw Accel schon benutzt oder prüfen willst, ob dir eine Kurve hilft. Die meisten Spieler bekommen die Antwort, dass keine Kurve eindeutig besser ist. Das ist ein normales Ergebnis.

Du brauchst eine Maus mit **1000 Hz oder mehr** Abtastrate. Lass Raw Accel in Windows ausgeschaltet: AimTune simuliert die Kurven selbst und ändert weder dein Spiel noch den Raw-Accel-Treiber.

1. Schließe zuerst Abschnitt 3 ab.
2. Im Hauptmenü **Raw Accel** öffnen (Taste **A**), dann **Find a curve** (Kurve suchen) wählen.
3. Spiele die Blind-Runden wie gewohnt. Du siehst nicht, welche Kurve gerade getestet wird.
4. Am Ende macht AimTune einen Blind-A/B-Test der Kurve gegen deine normale Sensitivity.
5. Eine Raw-Accel-`settings.json` wird **nur** exportiert, wenn das Ergebnis sagt, dass die Kurve eindeutig besser ist. Sonst lautet die Antwort „keine Kurve“.
6. Wenn du schon eine Raw-Accel-`settings.json` hast, wähle **Refine my curve** (Kurve verfeinern) und öffne die Datei mit **Open settings.json...**. Die Verfeinerung probiert nur kleine Änderungen aus und findet selten eine, die sich zu exportieren lohnt.

**Bitte melden:** ob du eine Kurve oder „keine Kurve“ bekommen hast, den genauen Text auf dem Results-Screen und deine aktuellen Raw-Accel-Kurvenwerte, falls du eine nutzt.

## 5. Ergebnis ansehen

Der Results-Screen zeigt:

- die **empfohlene Sensitivity**, als Spielwert und als cm/360°
- eine **Spanne** von Sensitivities, die ungefähr gleich gut abgeschnitten haben
- eine **Sicherheit** (Confidence)

Übernimm den empfohlenen Wert ins Spiel und spiele ein paar Tage damit, bevor du urteilst.

## 6. Daten teilen

1. In AimTune **History** öffnen.
2. **Share data for research** anklicken. Dabei entsteht eine Datei `aimtune-research-<datum>.json.gz` im Ordner `%APPDATA%\AimTune\exports\research\`. Diesen Pfad in die Adressleiste des Explorers einfügen, dann öffnet sich der Ordner direkt.
3. Diese Datei an den Entwickler schicken. Sie enthält keinen Namen, keine Dateipfade und keine Uhrzeiten, nur deine Runden, DPI und Sensitivity. Es wird nichts automatisch hochgeladen.
4. Bitte zusätzlich eine kurze Notiz schicken mit:
   - deinen Ergebnissen (empfohlene Sensitivity, cm/360°, Sicherheit)
   - allem, was unklar, verwirrend oder falsch wirkte
   - deiner Spracheinstellung und ob du im Vollbild oder im Fenster gespielt hast