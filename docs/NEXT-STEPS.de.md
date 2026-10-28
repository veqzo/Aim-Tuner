# Was jetzt zu testen ist und wie es weitergeht

Stand: Branch `dev`, nichts gepusht. Hintergrund und Rechnungen stehen in `docs/FIND-CURVE-REDESIGN.md` (Abschnitte 7 bis 13). Diese Datei sagt nur, was du tun sollst und was danach kommt.

Wichtig vorab: Ich habe nichts davon mit einer echten Hand und nichts in Raw Accel selbst geprüft. Alles unten ist die erste echte Probe. Die Schwellen im Plan (8 % spürbar, 1 und 2 SE, 2 Punkte Score, 8 Stunden, 2 bis 3 Sessions) sind Annahmen und können sich ändern.

## Teil 1: Was du testen sollst

### Test A: Analyse mit deiner Kurve (ca. 15 Runden, einmal)
1. Im Raw-Accel-Hub deine `settings.json` importieren (falls noch nicht geschehen). Raw Accel muss beim Spielen der Runden **aus oder linear** sein, die App spielt deine Kurve selbst.
2. "Find a curve" öffnen, **"Gegen meine eigene Kurve analysieren"** ankreuzen, dann **"Zuerst meine Fehler analysieren"** starten und die Kalibrierung zu Ende spielen.
3. Auf der Analyse-Seite die Karte **"Entwurf aus deinem Bewegungsprofil"** ansehen.

Was du mir zurückmeldest:
- Steht dort ein Schritt (+8, +15 oder +25 %), und ab welchen in/s beginnt er, ab welchen ist er voll?
- Oder steht dort ein Grund, warum **kein** Schritt angeboten wird (zu ruhige Hand, zu enges Geschwindigkeitsband, oder "schwächerer Anstieg")? Dann bitte den genauen Text, vor allem den "höchstens +x %"-Wert.
- Passen die Anker (Beginn und volle Stärke) zu dem, wie schnell du dich wirklich bewegst? Flicks zum Wegdrehen liegen vermutlich oberhalb.

### Test B: Stufe exportieren und in Raw Accel prüfen (nur wenn Test A einen Schritt zeigt)
1. Auf der Karte **"Stufe als Kopie speichern"**, im Export-Bildschirm speichern.
2. In Raw Accel das neue Profil **"<Profil> (AimTune)"** laden. Dein Originalprofil darf sich nicht geändert haben.
3. Den Graphen mit dem deines Originals vergleichen.

Erwartet: Unterhalb des Startwerts (der Median deiner schnellen Bewegungen) ist die Kurve gleich zu deinem Original. Darüber steigt der Gain sanft an und bleibt ab dem zweiten Anker um den Schritt (z. B. +8 %) über deinem Original. Der Modus ist `lut`, Velocity-Form.

Meldung an mich: Stimmt der Graph damit überein, oder ist etwas falsch (Kurve zu hoch/niedrig, Profil beschädigt, Datei lässt sich nicht laden)? Ein Fehler hier wäre ein echter Bug.

### Test C: Stufe spielen (über mehrere Tage)
1. Im Spiel oder Training die Stufe 1 spielen, **mindestens 2 Sessions an verschiedenen Tagen, mindestens 8 Stunden Abstand**.
2. Dazwischen, mindestens 2 Sessions, die auf deiner **eigenen Kurve** gespielt wurden (z. B. eine Analyse-Kalibrierung wie in Test A). Das ist der Vergleich für den Score.
3. In der App auf der Startseite von "Find a curve" den **Block "Dein Stufenplan"** ansehen und den Knopf **"Stufe 1 spielen"** ausprobieren. Er startet eine Kalibrierung, die die Stufe als Tabelle spielt.

Was du mir zurückmeldest:
- Wie fühlt es sich an? Schnelles Wegdrehen und Zurückkommen besser, schlechter oder gleich? Micro unverändert?
- Spürst du den Unterschied überhaupt (die 8 % sind nur eine Annahme)? Wenn nicht, wäre der nächste Schritt +15 %.
- Was steht im Plan-Block (Sessions je Stufe, Stand in Worten)? Springt er sinnvoll von "Vergleich fehlt" zu "Gewöhnung" zu "aufgestiegen"?
- Startet "Stufe 1 spielen" ohne Fehler und mit der richtigen Tabelle?

### Test D: Blindtest einer Stufe (optional)
Auf der Analyse-Seite **"Stufe 1 blind testen"**. Das Ergebnis ist eine **Warnung, keine Sperre**: Auch bei "deutlich schlechter" kann die Stufe als Kopie gespeichert werden, der Text rät dann nur zur eigenen Kurve. Prüfe, ob der Text verständlich ist.

### Was du nicht tun musst
- Keine Datenbank anfassen, keine Dateien aus dem Repo löschen. Deine `user-rawaccel-settings.json` und `stufe-*.json` sind gitignored und bleiben lokal.
- Die Erkennung "stärker oder schwächer" musst du nicht prüfen; sie ist absichtlich nur ein Hinweis.

## Teil 2: Was danach im Projekt kommt

Reihenfolge nach den Ergebnissen von Test A bis C.

1. **Schwellen kalibrieren (hängt an deinen Daten).** Zeigt die Karte bei dir keinen Schritt, ist die Streuungsgrenze `BANDWIDTH_Z` (0,5) vermutlich zu streng; ich prüfe sie dann gegen deine echten Erstbewegungen (nur aggregiert, nichts im Bericht). Ist +8 % nicht spürbar, ändere ich die Leiter. Passen die Aufstiegsschwellen nicht, ändere ich `StagePlan`.
2. **Schritt 6: Konstanz-Metrik.** Streuung der Erstbewegung innerhalb eines Geschwindigkeitsbins, entzerrt um die Distanz. Gehört in die Anzeige und in die Gewichte der Obergrenze, nicht in den Score. Erst sinnvoll definierbar, wenn echte Wiederholungen vorliegen (Test-Retest).
3. **Texte auf der Ergebnisseite.** Begründung, warum genau diese Stufe vorgeschlagen wird.
4. **Echte Spieler statt Simulation (R10).** Die simulierte Hand ist nicht an echten Spielern validiert, und ihr Geschwindigkeitsband ist zu eng, um einen Schritt anzubieten. Mit deinen Sessions und, wenn möglich, denen anderer Spieler lässt sich das prüfen. Dazu gehört auch die Gewöhnungsrate der Simulation.
5. **Aufräumen vor einem Merge.**
   - `ExportAuditTest.usersOwnProfile` schlägt an deiner lokalen Datei fehl (feste Sollwerte); entweder Test auf eine eigene Fixture umstellen oder Sollwerte aktualisieren.
   - `CLAUDE.md` hat eine ältere, nicht committete Änderung von dir.
   - Doku (`ARCHITECTURE.md`, `ALGORITHMS.md`, `DESIGN.md`, `PROGRESS.md`) ist nachgezogen; Screenshots liegen in `app/build/screenshots` (EN) und `app/build/screenshots-de` (DE).
   - Merge `dev` nach `main` erst nach deinem Test und nur auf deine Anweisung; ich pushe nichts.
6. **Langfristig.** Fallende Stufen (nur mit sehr starker Evidenz, `FALLING_MAX_HIGH` 0,90), Stufenplan über mehr Sprossen, Familie mit anderem Offset (bisher aus, weil sie die Bestätigungsrate senkte).

## Wenn etwas nicht funktioniert

Schick mir bitte:
- den genauen Text der Karte oder des Plan-Blocks,
- bei einem Export-Problem die Meldung des Export-Bildschirms (ohne den Inhalt deiner Datei),
- ob Raw Accel beim Spielen der App aus oder linear war.

Den Inhalt deiner `settings.json` brauche ich nicht und kopiere ich auch nicht in Berichte.
