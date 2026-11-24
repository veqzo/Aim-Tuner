# Tester-Guide: AimTune

## 1. Installieren

1. Die neueste `AimTune-<version>-windows.zip` aus den GitHub-Releases herunterladen und irgendwo entpacken.
2. `AimTune\AimTune.exe` starten.
3. Warnt Windows (SmartScreen): **Weitere Informationen** → **Trotzdem ausführen**. Das Programm ist noch nicht signiert.

## 2. Einmalig einrichten

Beim ersten Start fragt ein Setup-Screen ab:

- **Spiel** (Valorant) und deine **aktuelle Sensitivity** im Spiel
- **DPI** deiner Maus

Der Screen zeigt deine cm/360° live. Aim-Stil, Mauspad-Größe und Flash-Wegdrehen sind voreingestellt (Gemischt, Mittel, Manchmal). Ändere sie, wenn sie nicht passen. Später änderst du alle Werte über die **Setup-Pille** oben im Fenster.

Vorher in Windows **„Mauszeigerbeschleunigung verbessern“ ausschalten** (Einstellungen > Maus > Zusätzliche Mausoptionen > Zeigeroptionen). Falls du eine Raw Accel-Kurve nutzt, diese ebenfalls deaktivieren. Sonst werden die Messungen verfälscht. Die Check-Chips im Modul **Sensitivity** (Sensitivität) zeigen beide Zustände.

## 3. Testen

1. Im Dashboard im Modul **Sensitivity** (Sensitivität) auf **Find my sens** (Meine Sens finden) klicken (oder Enter drücken).
2. Eine Runde dauert **45 Sekunden**. Klicke die Ziele so schnell und genau wie möglich.
3. Nach jeder Runde siehst du, wie viele Ziele du getroffen hast und wie sicher sich das Programm ist. Die Sensitivity der Runde wird **absichtlich nicht angezeigt**. Also nicht darüber nachdenken, ob sie sich gut oder schlecht angefühlt hat.
   Ab der zweiten Runde startet ein **3-s-Countdown** die nächste Runde von selbst. Mit **Leertaste** pausierst du ihn.
4. Spiele **15 bis 30 Runden**, möglichst an **zwei verschiedenen Tagen**. Mit **Stop for today** kannst du jederzeit aufhören.

Steuerung: Klick startet eine Runde, `Esc` bricht ab, `Leertaste` pausiert den Countdown, `F11` schaltet Vollbild um.

Raw Accel ist nicht Teil dieses Tests. Abschnitt 4 erklärt die optionale Kurvensuche.

## 4. Optional: Raw-Accel-Kurve suchen

Mach diesen Teil nur, wenn du Raw Accel schon benutzt oder prüfen willst, ob dir eine Kurve hilft. Die meisten Spieler bekommen die Antwort, dass keine Kurve eindeutig besser ist. Das ist ein normales Ergebnis.

Du brauchst eine Maus mit **1000 Hz oder mehr** Abtastrate. Lass Raw Accel in Windows ausgeschaltet: AimTune simuliert die Kurven selbst und ändert weder dein Spiel noch den Raw-Accel-Treiber.

1. Schließe zuerst Abschnitt 3 ab.
2. Im Dashboard im Modul **Curve** auf **Find a curve** (Kurve suchen) klicken (Taste **A**).
3. Spiele die Blind-Runden wie gewohnt. Du siehst nicht, welche Kurve gerade getestet wird.
4. Am Ende macht AimTune einen Blind-A/B-Test der Kurve gegen deine normale Sensitivity.
5. Eine Raw-Accel-`settings.json` wird **nur** exportiert, wenn das Ergebnis sagt, dass die Kurve eindeutig besser ist. Sonst lautet die Antwort „keine Kurve“.
6. Wenn du schon eine Raw-Accel-`settings.json` hast, importiere sie im Modul **Curve** mit **Import Raw Accel profile** (Raw-Accel-Profil importieren) und klicke dann **Refine my curve** (Kurve verfeinern). Die Verfeinerung probiert nur kleine Änderungen aus und findet selten eine, die sich zu exportieren lohnt.

**Bitte melden:** ob du eine Kurve oder „keine Kurve“ bekommen hast, den genauen Text auf dem Results-Screen und deine aktuellen Raw-Accel-Kurvenwerte, falls du eine nutzt.

## 5. Ergebnis ansehen

Der Results-Screen zeigt:

- die **empfohlene Sensitivity**, als Spielwert und als cm/360°
- eine **Spanne** von Sensitivities, die ungefähr gleich gut abgeschnitten haben
- eine **Sicherheit** (Confidence)

Übernimm den empfohlenen Wert ins Spiel und spiele ein paar Tage damit, bevor du urteilst.

## 6. Daten teilen

1. In AimTune **History** (Taste **H**) oder **Settings** öffnen.
2. **Share data for research** (Daten für die Forschung teilen) anklicken. Dabei entsteht eine Datei `aimtune-research.json.gz` (ein neuer Export ersetzt sie) im Ordner `%APPDATA%\AimTune\exports\research\`. Diesen Pfad in die Adressleiste des Explorers einfügen, dann öffnet sich der Ordner direkt.
3. Diese Datei an den Entwickler schicken. Sie enthält keinen Namen, keine Dateipfade und keine Uhrzeiten, nur deine Runden, DPI und Sensitivity. Es wird nichts automatisch hochgeladen.
4. Bitte zusätzlich eine kurze Notiz schicken mit:
   - deinen Ergebnissen (empfohlene Sensitivity, cm/360°, Sicherheit)
   - allem, was unklar, verwirrend oder falsch wirkte
   - deiner Spracheinstellung und ob du im Vollbild oder im Fenster gespielt hast