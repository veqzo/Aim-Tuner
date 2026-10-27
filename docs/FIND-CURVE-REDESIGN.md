# Plan: Überarbeitung "Find a curve" (AimTune)

Status: Plan, nicht umgesetzt. Entworfen auf Branch `dev`, ohne Code. Der Nutzer entscheidet die offenen Produktfragen (Abschnitt 6) vor der Umsetzung.

## Context

"Find a curve" bietet praktisch nie eine Kurve an. Der Nutzer (800 DPI, 48.74 cm/360, eigene Raw-Accel-Kurve classic/in_out/Offset 10/Cap 62.5, Output 600 / Device 800) gehört selbst zur Zielgruppe "bewusst Accel". Die Frage lautet: Welche Form und welche maximale Stärke passt zum Bewegungsprofil? Nicht: ist Accel besser als linear? Eingewöhnung ist real, deshalb darf der Vergleich nicht "Kurve vs. Plain in einer Session" sein. Bestehende Regeln bleiben: `FALLING_MAX_HIGH` 0.90, Basis-Sensi fix, Export als Raw-Accel-`settings.json`, Anti-Cheat-Regeln.

## 1. Ist-Mechanismus: bestätigt, mit Korrekturen

Bestätigt (Zeilen gelesen):

| Aussage | Beleg |
|---|---|
| Phasen CALIBRATION, ANALYSIS, SEARCH, SEARCH_RESULT, TEST, DONE | `AccelRun.kt:29-47` |
| 15 Kalibrierrunden, min 8, min neue 3 | `AccelRun.kt:298,301,304` |
| Kalibrierung ist hart plain | `AccelRun.kt:180` (`accel = null`) |
| Analyse: MIN_SPEED 3, MIN_PER_BIN 20, 4..10 Bins | `AccelAnalysis.kt:96-105`, Binzahl `:92` |
| Fit: BIC 8, Gain-Range 1.02, Faktor ±10 % (`MAX_LOG_FACTOR`) | `AccelFit.kt:104-105,153` |
| 5-95-%-LUT (`BOUND_SHARE` 0.9), 32 Punkte | `AccelFit.kt:155-186` |
| A/B: alpha 0.01, minEffect 2, 12 Paare, min 6 | `AccelAb.kt:115-125` |
| Stärke 0.70-1.45 (1.75 mit Flash), DECAY 0.05, `FALLING_MAX_HIGH` 0.90 | `CurveStrength.kt:17-42,49,68` |
| Suche 24 Runden (min 16) | `StrengthSearch.kt:102-107` |
| Doku "adaptierte Hand exportiert fast nichts" | `ALGORITHMS.md:770` |

Korrekturen und Ergänzungen:
1. **Der Primärbutton "Search my curve" überspringt Kalibrierung und Analyse** (`AccelIntroScreen.kt:62`, `startWithSearch`, `AccelRun.kt:81-86`). Die Analyse-Route ist der Nebenpfad. Der Hauptpfad ist die 1-D-Stärkensuche auf der Familie `NaturalCurve(0.05, s)`; Form und Offset kommen nie aus den Daten.
2. **Export gibt es nur nach bestandenem A/B** (`AccelResultScreen.kt:37`, `ACCEL_BETTER`). `AccelSearchResultScreen` exportiert nichts.
3. **Kalibrierdaten sind auf plain festgenagelt**, auch im Speicher: `plainAccelRoundIds` verlangt `r.accel IS NULL` (`RoundRepository.kt:130-147`). Die Kurvenrunden des Nutzers (Refine = `accel_refine`) sind ausgeschlossen.
4. **`StoredSearches.strengthOf` kennt nur NaturalCurve mit decay 0.05 und `inputOffset == 0`** (`StoredSearches.kt:46-59`). `checkNotNull` in `priorRounds` (`:116`) bricht bei neuen Familien. Jede neue Familie braucht ein totales `strengthOf`.
5. **Die Raw-Accel-Prüfung bleibt nötig und ist kein Blocker.** Die App wendet die Kurve selbst an, der Treiber muss aus oder linear sein (`AccelIntroScreen.kt:74`, `AppContext.kt:372`). Sie bleibt, nur der Text ändert sich.
6. Das Profil des Nutzers ist darstellbar: `RawAccelProfile.totalMultiplier = curve * outputDpi / deviceDpi` (`RawAccelProfile.kt:89`). `RawAccelCurve` kann classic mit Offset und alle Cap-Modi (`RawAccelCurve.kt:64-148`). `finderCurve = null` (`:94`), das Profil speist den Finder nicht.
7. **Wichtige Befunde der Refine-Gates (M22)** (`RAWACCEL-HUB.md:21-48`, `ALGORITHMS.md` "Refine my Raw Accel curve", `PROGRESS.md:235`): Die Standardhand ist 3-4 mal langsamer als die des Nutzers; mit ihr liegen nur 3.7-9.5 % der Gain-Auswertungen über dem Offset. Die an die gespeicherte `accel_refine`-Session (25 Runden) angepasste Hand (`AIMTUNE_HAND=real`; Fitts-Intercept 40, Slope 25, 300 cm/s) hat 35 % der Reports über 10 in/s und 22 % über 20 in/s. Auch dort bewegen die Knöpfe des Profils den Multiplikator nur um 1-3 Punkte, die Refine-Seite trifft nur zufällig (42-56 %), es wird nichts exportiert. Folge für Schritt 5: Die Geschwindigkeitsbasis ist vorhanden, die Identifizierbarkeit der Stärke bleibt das Risiko. Schritt 0 muss sie für den Finder-Pfad messen, nicht für Refine.
8. Pfad-Korrektur: `docs/ANALYSE.md` existiert nicht mehr (`PROGRESS.md:246`). Gate-Berichte liegen in `core/build/reports/harness/` (gitignored).

Offen, nicht geprüft: Ob `AccelAnalysis`-Gain-Punkte relativ zur gelieferten Kurve gelten. Der Code normiert auf den Median (`:28-36`, "nur Form"), die Nutzer-DB wurde in diesem Plan nicht erneut geöffnet.

## 2. Design Schritte 0-7

### Schritt 0: Simulation des Referenzfalls (kein Produktcode)
- Neuer gated Test `core/src/test/.../harness/review/FindCurveReferenceGateTest.kt` (`@EnabledIfEnvironmentVariable AIMTUNE_REVIEW`), Muster `RefineGateTest.kt:99-100,155-161` und `GateHarness`. Bericht: `write("gate-find-redesign-0.txt", …)`.
- Spieler: `HandAdaptation.adaptedTo(pref, delivered = userCurve)` (frisch pro Person), `userCurve` = `RawAccelProfile` des Nutzers (aus `RefineGateTest:99-100`). Hände: DEFAULT und `AIMTUNE_HAND=real` (`RefineGateTest:60-69`).
- Wahrheiten: (a) bevorzugt die eigene Kurve, (b) ×0.5 Stärke, (c) ×2 Stärke, (d) flach/plain bevorzugt, (e) Form andere Familie (natural, späterer Offset).
- Pfad: der heutige volle Pfad, einmal als Suche (`StrengthSearch`/Gate-Emulation `GateHarness:378-404`), einmal als Kalibrierung, Analyse, Fit, A/B (`runAccelPipeline`, `AccelPipelineTest:34-60`).
- Messgrößen: Angebotsrate, mittlere |Δ Multiplikator| zur Wahrheit im 5-95-%-Geschwindigkeitsbereich, schädliche Angebote, Handgeschwindigkeits-Quantile q5/q50/q95 (Anteil Auswertungen über Offset).
- Die Geschwindigkeitsverteilung der echten Hand liegt bereits als Kalibrierung vor (`RefineSpeedCalibrationTest`, `AIMTUNE_HAND=real`); Schritt 0 nutzt sie und liest die Datenbank nicht erneut. Nur falls der Finder-Pfad andere Szenarien spielt (z. B. ohne Flash), wird die Verteilung dort nachgerechnet, wieder ohne Nutzerdaten in Berichten.
- **Entscheidungsregel:** Liegt der Anteil über dem Offset weiter unter ~15 %, sind Schritte 5-6 (Stärke aus Profil) nicht identifizierbar. Dann wird Schritt 5 auf "Obergrenze nur aus Fehlerband" reduziert und die Empfehlung ehrlich als Richtung, nicht als Optimum formuliert.

### Schritt 1: Kalibrierung gegen die eigene Kurve
- Datenmodell: `AccelBaseline` (cm/360) bekommt `curve: RawAccelProfile?`. `AppContext.startAccelTest` (`:354-368`) setzt sie aus dem Hub-Import (`HubSession.imported`, `ImportedProfile.profile(dpi)`), sonst null (Plain).
- `AccelRun.nextRoundConfig` (`:176-180`): `accel = baseline.curve` statt `null`. Kalibrierung und Suche laufen dann mit der gleichen Basis.
- Storage: neue Abfrage `calibrationRoundIds(spec, dpi, cm360, scenarios, tol, limit)` mit exaktem `r.accel = ?` (kein Schema-Umbau), `plainAccelRoundIds` bleibt für Plain. Index auf `round.accel` nur, wenn die Messung es verlangt (Migration 3).
- Analyse: `AccelAnalysis` nutzt die Roheingangsgeschwindigkeit (`AccelProcessor.speedInPerSecond`, `:45`), der Gain-Punkt ist die gewünschte Korrektur relativ zum gelieferten Gain. **Zu prüfen in einem ersten Test:** ob `logGain` als Korrektur auf die Basis gelesen werden darf. Zielkurve = `base(v) * correction(v)`.
- Session-Ebene: neue Spalte `session.baseline TEXT` (Migration 3, Spec der Basis), damit spätere Sessions sauber vergleichen. Neue Session-Art `accel_plan` (nur ergänzen, nie bestehende Migrationen ändern).
- Intro-Text: Baseline-Karte zeigt "Deine Kurve (importiert)" oder "Linear".

### Schritt 2: Suchfamilie erweitern
- `AccelFit` classic: Parameter `inputOffset` als vierter Fit-Parameter (Grenzen aus `RawAccelArgs`, `capX >= inputOffset`), BIC-Strafe `ln(nBins)` pro Parameter bleibt (`:66`). Startpunkt der Suche = Fit (Form), nicht NaturalCurve.
- `CurveStrength` wird zu `ShapedStrength(shape, s)`: ein Skalar auf dem Log-Überschuss einer gewählten Form (Form kommt aus Fit, ersatzweise Basis). Suche bleibt 1-D (`SensitivityOptimizer`, Pseudo-cm/360), also gleiche Statistik und Gates wie heute.
- `StoredSearches.strengthOf` (`:46`) wird total und erkennt Formtag. Die Altform (natural 0.05) bleibt lesbar.
- Falls-Regel unverändert: `excludesPlain` mit `FALLING_MAX_HIGH`. Anpassung: "plain" heißt jetzt "Basis" (Nutzerkurve oder linear).

### Schritt 3: A/B nur als Schutz
- `evaluateAb` bleibt, aber nur über Sessions: `PLAIN`-Arm wird zur Basiskurve (wie Refine, `AccelAb.kt:12-16`). Fehlt der Vergleich, ist das kein Blocker für die Empfehlung (Produktentscheidung). Der Test warnt nur ("deutlich schlechter als deine Basis"), er schaltet nicht frei.
- Kein neuer Stat-Test. `ACCEL_WORSE` (Mittel ≤ −2 und obere Grenze < 0) bleibt der Schutzfall; `groupByScenario` bleibt aus (`AccelAb.kt:83-86`).

### Schritt 4: Stufenplan
- Stufe 1 = Basis plus halbe gefundene Stärke (`s1 = 1 + (s* − 1)/2` im Log-Überschuss).
- Aufstieg zur nächsten Stufe nur, wenn (i) die Fehlerkurve in Richtung Ziel läuft (gemessene `correction(v)` verkleinert sich in mindestens der Hälfte der Bins um ≥ 1 SE) **und** (ii) der Score nicht unter die eigene Zeit-Baseline fällt (Median der Scores früherer Sessions mit gleicher Basis und cm/360; Session-Offsets des Optimizers sind vorhanden).
- Persistenz: Plan = Folge von `accel_plan`-Sessions mit `baseline` und Stufe in der Spec. Die Wiederaufnahme nutzt `priorSearch`/`PriorCalibration` (nach Basis gefiltert, Schritt 1).
- Jede Stufe liefert eine importierbare Datei über `RawAccelExport.patch`/`PatchedCopy` (nur Kurvenfelder, alles andere Original).

### Schritt 5: Stärke aus dem Bewegungsprofil
- Obergrenze `s_max`: größtes s, bei dem die vorhergesagte Überschießung im Bereich q5-q95 der Handgeschwindigkeit (`speedQuantile`, `AccelAnalysis.kt:56`) unter der gemessenen Fehlerbandbreite pro Bin bleibt: `|Δ ln gain(v)| ≤ z · sd_bin(v)`.
- Gewichte (Heuristik, Validierung nötig): Micro-Anteil (`MICRO`-Runden, `FLASH` eher oben) und Konstanz (Schritt 6) setzen `z` zwischen 0.5 und 1.0. Mehr Micro und niedrige Konstanz verkleinern `z`.
- Ergebnis = `min(s* aus Suche, s_max)`; Gate: nie über `MAX`/`MAX_WITH_FLASH`.

### Schritt 6: Konstanz als Metrik
- Vorschlag: Streuung von `ln(1+e)` (erster Reach) innerhalb eines Geschwindigkeitsbins, entzerrt um Distanz (Lehre aus R3: Konsistenz gehört nicht in die Sensi-Zielfunktion). Reporting und Gewicht in Schritt 5, nicht im Score.
- Validierung: Test-Retest zwischen Sessions in der Simulation (ICC), Unterscheidbarkeit zwischen DEFAULT und DECOUPLED-Rauschen, Prüfung, ob sie von Distanz/Geschwindigkeit entkoppelt bleibt (corr(ln dist, ln speed) = 0.956, `PROGRESS.md` M14).

### Schritt 7: UI und i18n
- Neue/angepasste Keys in `assets/i18n/messages.properties` und `messages_de.properties` (Bereiche `accel.*` ca. 243-301, 361-393; `hub.curve.*` 485-490):
  - `accel.baseline.own` / `accel.baseline.plain` (Basis-Karte)
  - `accel.result.reason.*` (Begründung: Form, Bereich, Stärke)
  - `accel.plan.stage` ("Stufe 1 von 3 – halbe Stärke"), `accel.plan.advance`, `accel.plan.hold`
  - `accel.result.export_staged`, Hinweis "nicht gegen Linear getestet, bewusst gewählte Richtung"
- Screens: `AccelIntroScreen` (Basis-Karte, Start), `AccelSearchResultScreen` (Begründung, Stärke, Plan, Export), `AccelBetweenScreen` (Erklärung Basis). `AccelResultScreen:37` entkoppelt Export vom `ACCEL_BETTER`.
- `BundlesTest` (en/de gleiche Keys) und Screenshots `:app:screenshots`, `:app:screenshotsDe` ergänzen. `docs/DESIGN.md` bei Abweichungen.

## 3. Testplan (TDD)

Zuerst (rot, vor Code):
1. `AccelFitClassicOffsetTest`: Fit findet bekannte classic-Kurve mit Offset (Recovery, 4 Parameter, BIC nicht überfüllt).
2. `ShapedStrengthTest`: s=1 gibt Basis, Spiegelung, `excludesPlain` mit Basis.
3. `StoredSearchesTest` erweitert: `strengthOf` total, keine `checkNotNull`-Ausnahme bei neuer Familie, Altdaten lesbar.
4. `RoundRepository`-Test: `calibrationRoundIds` trennt Plain, Kurve X, Refine.
5. `AccelRunTest`: Kalibrierung mit Basiskurve spielt `accel = baseline`, Analyse liefert Korrektur relativ zur Basis (Annahme aus Schritt 1 als Test).
6. `StagePlanTest`: Aufstiegsregel (Fehler Richtung Ziel, Score gegen eigene Baseline).
7. `StrengthCeilingTest`: `s_max` fällt mit kleiner Fehlerbandbreite und bei Micro-Anteil.
8. `BundlesTest`, `PatchedCopyTest`-Fall für Stufen-Export.

Simulation (Gate-Muster, `AIMTUNE_REVIEW`, Berichte `gate-find-redesign-N.txt`):
- N0 Referenzfall (oben), Wiederholung nach jedem Schritt.
- Null-Gate: flache Spieler und Spieler, die die Basis bevorzugen, werden nicht verändert (≤ 5 % Angebote wie `REFINE_NULL`, `GateNull.kt:20`).
- Detektion: Wahrheit ×0.5 und ×2 muss in die richtige Richtung zeigen (Ziel ≥ 70-80 % wie im Refine-Gate).
- Schädlichkeit: Wahrheit-Richtung nie umgedreht, Stufe 1 nie schlechter als Basis − Rauschen.

Akzeptanz je Schritt:
- S0: Bericht liegt vor, Entscheidungsregel angewendet, Nutzerprofil-Referenz benannt.
- S1: Kalibrierung mit Nutzerprofil läuft headless (`SampleAccelRun`-Muster), Analyse reproduziert die eigene Kurve bei Wahrheit (a) mit mittlerer |Δ Multiplikator| < Rauschen.
- S2: Wahrheit (e) wird mit passender Familie erkannt; nichts regrediert in `CurveStrengthTest`, `GateTest`.
- S3: keine zusätzlichen falschen Verdikte (Null ≤ 5 %).
- S4: Aufstieg nur bei erfüllter Regel; Stufe 1 liegt zwischen Basis und Ziel.
- S5: `s_max` begrenzt in jedem Gate-Szenario.
- S6: ICC dokumentiert; wenn schlecht, bleibt Konstanz nur Anzeige.
- Gesamt: Referenzfall liefert eine Kurve nahe dem Profil oder eine begründete Verfeinerung, nicht linear. Wo die Simulation das nicht kann (Befund 7), steht das so im Bericht.

## 4. Annahmen und Validierung
- **Gewöhnung vs. Präferenz** ist mit der Kurvenfolge allein nicht trennbar. Mitigation: Zeit-Baseline und Stufenplan. Prüfen durch Simulation mit `HandAdaptation` (Smith 2006, Defaults ALGORITHMS:742), Gate-Szenarien "adaptiert" und "noch nicht adaptiert".
- **Lerngeschwindigkeit**: Parameter stammen aus Reach-Bewegungen, nicht aus Aim-Training. Prüfen durch Sensitivität (Raten ×0.5/×2).
- **Handgeschwindigkeit**: aus 25 Runden einer Session kalibriert (Fitts-Terme angepasst, Erstschussgenauigkeit simuliert 1.00 gegen echt 0.75, `PROGRESS.md:235`). Prüfen in Schritt 0 mit `AIMTUNE_HAND=real` und Sensitivität.
- **Konstanz-Definition**: Test-Retest und Entkopplung von Distanz (Schritt 6).
- **Anfänger**: Stufenplan nimmt die halbe Stärke; nicht an echten Anfängern validiert (Simulation, R10 offen).
- **Der simulierte Spieler ist nicht an echten Spielern validiert** (Audit F11, R10 offen).

## 5. Priorisierte Änderungsliste

| # | Schritt | Aufwand | Abhängig von |
|---|---|---|---|
| 1 | S0 Referenzsimulation + Geschwindigkeitsprofil | M | – |
| 2 | S1 Kalibrierung gegen Basiskurve (+ Migration 3) | L | S0 |
| 3 | S2 Fit mit Offset, `ShapedStrength`, `strengthOf` | M | S1 |
| 4 | S3 A/B als Schutz | S | S1 |
| 5 | S6 Konstanz definieren + validieren | M | S0 |
| 6 | S5 Stärke aus Profil | M | S2, S6, Entscheidungsregel S0 |
| 7 | S4 Stufenplan + Persistenz | L | S1, S2 |
| 8 | S7 UI/i18n/Doku | M | alle |

Hand-off an Refine: Der Finder liefert ein importierbares Profil (`PatchedCopy`), Refine startet dort und ändert nur die zwei Faktoren (`RefineParameter`, `RefineShape`-Whitelist).

## 6. Produktentscheidungen

Entschieden (Nutzer, Punkt 5 als Empfehlung angenommen):
1. **Empfehlung:** Es wird empfohlen, sobald brauchbare Daten da sind. Nur wenn es absolut kein klares Ergebnis gibt (zu wenige Bewegungen, zu enger Geschwindigkeitsbereich, Fit nicht besser als die Basis), entfällt der Vorschlag, und dann nennt der Bericht, was fehlt und wie man es bekommt. Eine Finder-Funktion, die nie eine Kurve vorschlägt, verfehlt das Ziel des Raw-Accel-Teils. Das Gewicht liegt auf "Richtung plus Stufe 1" statt auf "bewiesen besser".
2. **Schutz-Test:** nur warnen, nicht sperren.
3. **Export ohne bestandenen A/B:** erlaubt, mit Hinweis "nicht gegen deine Basis bewiesen".
4. **Basis ohne importierte Kurve:** Plain-Basis, gleicher Pfad wie mit Kurve. Kalibrierung gegen linear, Stufe 1 mit halber gefundener Stärke, Steigerung über Sessions.
4b. **Quelle der Basiskurve:** Import der `settings.json` oder Handeingabe (Modus, Beschleunigung, Offset, Cap) für Spieler anderer Accel-Software. Die Handeingabe braucht dieselbe Validierung wie `RawAccelArgs` (`capX >= inputOffset`, `in_out` braucht `capX > 0` und `capY > 0`, `RawAccelArgs.kt:64-70`).
5. **Schritt 0 / Daten:** Zweistufig. Schritt 0 läuft jetzt mit der vorhandenen Kalibrierung der echten Hand (`AIMTUNE_HAND=real`), das kostet den Nutzer nichts. Sobald Schritt 1 steht, spielt der Nutzer 2-3 Sessions mit seiner eigenen Kurve an verschiedenen Tagen. Diese Runden validieren die Simulation (R10), liefern die Zeit-Baseline für Schritt 4 und zeigen als Erste echte Daten zu Gewöhnung gegen Präferenz. Optional: eine kurze Plain-Session (15 Runden bei 48.74 cm/360) für den Nachwirkungs-Vergleich.

## 7. Ergebnis Schritt 0 (Simulation, 24 Läufe je Zeile, Stand `0ac6a7c` + neuer Test)

Test: `core/src/test/.../harness/review/FindCurveReferenceGateTest.kt` (`AIMTUNE_REVIEW`), Berichte `core/build/reports/harness/gate-find-redesign-0-{real,default}.txt` (gitignored). Aufruf: `AIMTUNE_REVIEW=1 ./gradlew.bat :core:test --tests "*FindCurveReferenceGateTest*" --rerun-tasks`. Hand "real" = an die gespeicherten Runden des Nutzers angepasst (48.74 cm/360), kalibriert auf Sensi-Faktor 1.00 (0.995-1.002). Hand "default" ist die Standardhand (55 cm/360); ihre Kalibrierung ist nicht konvergiert (1.08 / 1.17 / 0.93), ihre Zeilen gelten nur als Gegenprobe.

Wahrheiten: OWN (Wunsch = dein Profil), CAP x0.5, CAP x2.0, TRANS x2.0, NATURAL (limit 1.3), PLAIN. Die Hand ist jeweils an dein Profil gewöhnt.

**SEARCH (heutiger Hauptpfad), Hand real:** Angebot (Intervall schließt "keine Kurve" aus)

| Wahrheit | Angebot | Stärke (Median) |
|---|---|---|
| OWN | 29 % | 1.04 |
| CAP x0.5 | 25 % | 1.06 |
| CAP x2.0 | 25 % | 1.05 |
| TRANS x2.0 | 25 % | 1.06 |
| PLAIN | 21 % | 1.04 |
| NATURAL 1.3 | 100 % | 1.38 |

**CALIB-PLAIN (heutiger Analysepfad), Hand real:** 0 % Angebot bei allen Profil-Wahrheiten, 54 % nur bei NATURAL. Gebrauchte Erstbewegungen: q5/q50/q95 = 3.4 / 6.7 / 16 in/s, 27 % über dem Offset (10), 0.5 % über 20.

**CALIB-OWN (Vorschau Schritt 1), Hand real:** erwartete Korrektur (Signal) gegen Rauschen je Bin (log)

| Wahrheit | Signal | Rauschen | Angebot |
|---|---|---|---|
| OWN | 0.0000 | 0.0067 | 4 % |
| CAP x0.5 | 0.0014 | 0.0066 | 8 % |
| CAP x2.0 | 0.0026 | 0.0067 | 33 % |
| TRANS x2.0 | 0.0028 | 0.0066 | 0 % |
| PLAIN | 0.0028 | 0.0066 | 0 % |
| NATURAL 1.3 | 0.0322 | 0.0069 | 96 % |

### Was daraus folgt
1. **Der heutige Pfad ist für Kurven in der Familie deines Profils blind.** Das Angebot hängt in SEARCH nicht von der Wahrheit ab (21-29 %, auch bei PLAIN), nur die breite NATURAL-Form wird gefunden. Ob die 21-29 % "falsche Angebote" im Sinn der früheren Gates sind, ist nicht vergleichbar: dort zählte der Export nach dem A/B (ALGORITHMS.md:800-813), hier das Angebot vor dem A/B.
2. **Die Kalibrierung gegen die eigene Kurve (Schritt 1) allein löst es nicht.** Die Korrektur, die dein Profil von seinen Nachbarn unterscheidet, ist 0.1-0.3 % je Bin gegen 0.67 % Rauschen (Verhältnis 0.2-0.4). Bei NATURAL ist das Verhältnis 4.7 und der Pfad funktioniert. Rauschen fällt mit 1/sqrt(Runden): für CAP x2.0 wären grob 6x so viele Runden (~90) nötig, für CAP x0.5 über 20x. Das ist grob geschätzt, nicht simuliert.
3. **Die Entscheidungsregel aus Abschnitt 2 (Anteil über Offset < ~15 %) war die falsche Kennzahl.** 27 % liegen über dem Offset, trotzdem ist das Signal klein, weil die Kurve zwischen 10 und 20 in/s (Beschleunigung 0.005, Cap erst bei 62.5) kaum steigt. Die Kennzahl ist Signal / Rauschen. Neue Regel: Schritte 5-6 sind für eine Familie identifizierbar, wenn das Signal bei 15-25 Runden mindestens das Rauschen erreicht.
4. **Folge für die Produktentscheidung "immer empfehlen":** Für die Profil-Familie kann eine Empfehlung nicht aus der Präferenzschätzung kommen, sie wäre Zufall. Sie kann aus dem Bewegungsprofil kommen (Schritt 5 als Entwurfsregel statt als Schätzung): Form und Stärke aus den gemessenen Geschwindigkeitsquantilen und der Fehlerbandbreite, mit ehrlicher Beschriftung "Entwurf aus deinem Bewegungsprofil, nicht aus einer Präferenz gemessen". Das passt zu "Richtung plus Stufe 1" und zum Stufenplan (Schritt 4), der die Wirkung über Sessions prüft.

### Grenzen dieses Befunds
- Simulierte Hand. Der Erstbewegungs-Speed (q95 16 in/s) ist eine andere Größe als die Geschwindigkeit je Report, mit der die Hand kalibriert wurde (`PROGRESS.md:235`: 22 % über 20 in/s). Wie beide zusammenhängen, ist nicht geprüft.
- Alle Wahrheiten ändern nur die Gain-Form; ein echter Spieler kann auch eine andere Basis-Sensi wollen (Audit F4).
- 24 Läufe je Zeile: Raten sind auf etwa ±10 Punkte genau, nicht mehr.
- Die Handeingabe von Schritt 2 (classic mit Offset) und Schritt 6 sind hier noch nicht getestet.

## 8. Stand Schritt 1 (Kalibrierung gegen die eigene Kurve)

Umgesetzt (Analysepfad "Analyse my errors first"; die Stärkesuche bleibt bis Schritt 2 unverändert):
- `AccelAnalysis(delivered = …)`: Die gelieferte Kurve wird aus dem Fehlerverhältnis jeder Bewegung herausgerechnet (`ln(1+e) − ln d(v)`), die Punkte sind dann der gewünschte Gain-Verlauf und nicht die Korrektur. Ohne Kurve (`null`) ist das Ergebnis identisch zu vorher (Test).
- `AccelFit.scaledBy(level)`: Die Analyse zentriert auf den Median, der Fit startet also bei 1. Der Pegel der Basis am typischen Tempo wird in eine Tabelle (`LookupCurve`) übernommen, denn eine Familie startet bei 1 und kann 0.75 nicht abbilden. Annahme: Die Sensitivity stimmt beim typischen Tempo, nur die Form ändert sich.
- `AccelRun(baseCurve = …)`: Kalibrierrunden spielen die Basiskurve; der Kontrollarm des Blindtests spielt ebenfalls die Basis (nicht linear). Mit Basiskurve ist der Fit immer eine Tabelle.
- Speicher: `RoundRepository.accelRoundIds(spec, …)` (exakter Treffer auf den gespeicherten Kurventext, Refine-Sitzungen zählen mit: ihre Warm-up-Runden spielen das importierte Profil unverändert), `RoundProcessor.priorCalibration(…, baseCurve)`.
- UI: Intro-Karte "Gegen meine eigene Kurve analysieren: <Datei · Profil>" mit Hilfetext, oder der Grund, warum die Kurve nicht genutzt wird (DPI weicht ab, keine Geräte-DPI, nicht exakt abspielbar). EN und DE.
- Abweichung vom Plan: keine Migration 3 und keine Spalte `session.baseline`. Die Basis ist aus dem gespeicherten Kurventext der Runden ableitbar, ein Schema-Umbau lohnt sich erst mit dem Stufenplan (Schritt 4).

Tests: `AccelAnalysisDeliveredTest` (5), `AccelRunTest` (+3), `RoundRepositoryTest` (+1), `RoundProcessorTest` (+1).

Bekannte Einschränkungen:
- Der Fit trifft den Verlauf am langsamen Ende bis zu 6 % daneben (mit und ohne eigene Kurve identisch, Kontrolle im Test).
- Aus Schritt 0 gilt weiter: Ob eine Abweichung in der Familie deines Profils erkennbar ist, entscheidet 15 Runden nicht (Signal 0.1-0.3 % gegen 0.67 % Rauschen). Schritt 1 macht die Basis richtig, nicht die Messung genauer.
- `ExportAuditTest.usersOwnProfile` (storage) schlägt fehl, weil `user-rawaccel-settings.json` im Repo-Root von den hartkodierten Sollwerten (Wert bei 20 in/s: erwartet 1.0286, gelesen 1.0310) abweicht. Er prüft eine eigene Referenzimplementierung und die lokale Datei, nicht den geänderten Code.

## 9. Stand Schritt 2 (Suchfamilie)

Umgesetzt:
- `AccelFit`: neue Familie `classic+offset` (4 Parameter, Offset 1-30 in/s, BIC-Strafe wie jede andere Familie), per `fitAccel(withOffsetFamily = true)` zuschaltbar, **standardmäßig aus** (siehe unten). Gate `OffsetFamilyGateTest` (`gate-find-redesign-2-offset.txt`, 15 Runden, 4 Spielermodelle): Flache Spieler bekommen mit und ohne die Familie gleich oft ein Angebot (DEFAULT, DECOUPLED, ADAPTIVE 0 von 200; RANGE 200 von 200 in beiden Spalten, die bekannte Range-Bias-Schwäche). Erkennung von +-15 % und +-30 % unverändert bis auf einen Lauf (RANGE -30 %: 26 auf 27). Bei -30 % wird die neue Familie oft gewählt (24 von 30, DEFAULT).
  - **Nicht übernommen:** Dieses Gate zählte nur Angebote, nicht die Qualität der Kurven. `AccelPipelineTest` ("the blind test confirms a real improvement ...") fiel mit der Familie auf 29 bestätigte von rund 60 Angeboten (Schwelle 60 %), ohne sie besteht er. Die Familie passt für Spieler mit fallender Präferenz besser an die Bins an, die Kurven bestehen den Blindtest aber seltener. Deshalb ist sie vorhanden und getestet (Offset wird wiedergefunden), aber nicht Teil der Empfehlung. Ob sie mit einer eigenen Basiskurve hilft, ist nicht gemessen.
- `OwnCurveStrength` (core): Die Stärke `s` skaliert den Anstieg der eigenen Kurve auf der Log-Skala, `C_s(v) = B(low) * (B(v)/B(low))^s`. 1 ist die Kurve selbst (innerhalb des Neutralbands wird das Profil direkt gespielt), Bereich 0.5 bis 2.0, gespielt als 64-Punkte-Tabelle. `strengthOf` liest `s` aus der gespeicherten Tabelle zurück.
- App: `SearchBase`, `StrengthSearch(base)`, `AccelRun` (Suche, Angebot und Blindtest auf der eigenen Kurve), `StoredSearches.strengthOf/priorRounds/pool(base)`: Sitzungen einer anderen Basis werden übersprungen statt zu raten (vorher `checkNotNull`), `RoundProcessor.priorSearch/lastSearch(baseCurve)`, Ergebnis- und "Last search"-Texte für die eigene Kurve (EN/DE).
- Tests: `OwnCurveStrengthTest` (5), `OwnCurveSearchTest` (4), `AccelFitTest` (+2).

**Gate-Ergebnis der Suche auf der eigenen Kurve (SEARCH-OWN, 24 Läufe je Zeile, Hand real):** Akzeptanz verfehlt.

| Wahrheit | Angebot | richtige Seite | falsche Seite | Stärke (Median) | am Rand |
|---|---|---|---|---|---|
| OWN (Null) | 12.5 % | - | - | 1.22 | 42 % |
| CAP x0.5 | 8.3 % | 4.2 % | 4.2 % | 1.12 | 63 % |
| CAP x2.0 | 20.8 % | 16.7 % | 4.2 % | 1.16 | 38 % |
| TRANS x2.0 | 20.8 % | 12.5 % | 8.3 % | 1.12 | 50 % |
| PLAIN | 25.0 % | 12.5 % | 12.5 % | 1.10 | 54 % |

- Die Null-Rate (12.5 %) liegt über dem Ziel von 5 %, die Richtung wird nicht gefunden (richtig und falsch etwa gleich oft, die Mediane liegen alle bei 1.1-1.2). 40-60 % der besten Werte liegen am Rand der Suche. Die Standardhand liefert dasselbe Bild.
- Das deckt sich mit Schritt 0 und dem Refine-Befund: Auf diesem Geschwindigkeitsbereich ändert die Skalierung des Anstiegs den Score um nur ein bis drei Punkte, die Streuung der Rundenscores ist größer.
- Folge: Die Suche auf der eigenen Kurve ist als Hinweis nutzbar, nicht als Messung. Ein Angebot daraus ist wie das alte "ein Hinweis, kein Ergebnis" zu beschriften (der Text sagt das schon: "Test it blind ..."), und es darf nicht als gemessene Präferenz auftreten. Die Stärke muss in Schritt 5 aus dem Bewegungsprofil kommen, wie in Abschnitt 7 begründet.
- Offen, nicht untersucht: ob ein größerer Suchbereich oder ein anderer Rundenmix die Richtung verbessert. Ich habe es nicht probiert, weil die Streuung der Scores das Hauptproblem ist und Schritt 5 das Problem anders löst.

## 10. Stand Schritt 5 (Stärke aus dem Bewegungsprofil)

Umgesetzt (`core/.../accel/StrengthDraft.kt`, `draftStrength`):
- **Schätzung:** gewichtete Regression von (gewünschter Log-Gain minus gelieferter Log-Gain) auf den gelieferten Log-Gain je Geschwindigkeits-Bin. Die Steigung ist "Stärke minus 1" (Stärke wie in `OwnCurveStrength`, 1 = deine Kurve).
- **Obergrenze und Untergrenze:** Die Änderung des Gains `(Stärke - 1) * Anstieg(v)` bleibt in jedem Bin zwischen dem 5. und 95. Perzentil der Handgeschwindigkeit innerhalb von `BANDWIDTH_Z` (0.5) Streuungen des Erstbewegungsfehlers. Das Ergebnis liegt in 0.5 bis 2.0.
- **Richtung:** nur dann "gemessen", wenn (a) die 80-%-Spanne der Schätzung auf einer Seite von 1 liegt (schwächer nur unter `FALLING_MAX_HIGH` 0.90) **und** (b) die geschätzte Änderung des Log-Gains über den gemeinsamen Bereich größer ist als `TREND_ALLOWANCE` (0.06). Sonst gilt die Standardrichtung "stärker" (dein Ziel: hoher Gain für schnelle Drehungen, Micro unverändert), mit `DESIGN_SHARE` (25 %) des Wegs bis zur Obergrenze.
- **Stufe 1** ist die Hälfte des Entwurfs. `AccelRun.draft` / `draftOffer` / `startDraftTest()` testen Stufe 1 blind gegen die eigene Kurve; der Analyse-Bildschirm zeigt eine Karte "Entwurf aus deinem Bewegungsprofil" mit dem Hinweis, dass der Wert nicht gemessen ist (EN/DE).
- Tests: `StrengthDraftTest` (8), `AccelRunTest` (+2).

**Gate-Ergebnis (DRAFT in `gate-find-redesign-0-{real,default}.txt`, 24 Läufe je Zeile):**
- Erster Lauf: Die Schätzung lag auch bei Wahrheit "meine Kurve" bei 2.2 und war in 100 % der Läufe "stärker, gemessen". Ursache: Das Profil steigt im Bereich der Hand um nur etwa 0.03 im Log; ein kleiner Geschwindigkeitstrend im Fehler (Range-Bias, M14) wird durch diese kleine Spanne um das 30-Fache aufgeblasen. Die Messung dieser Familie hat deshalb schon in Schritt 0 nicht getragen.
- Gegenmaßnahme `TREND_ALLOWANCE`. Danach ist die Basis für alle sechs Wahrheiten Standardrichtung (kein falsches "gemessen"): Entwurf 1.25, Stufe 1 = **x1.125** des Anstiegs deiner Kurve, Obergrenze 2.0 (deine Kurve wirkt im Bereich deiner Bewegungen kaum, das Fehlerband lässt die ganze Spanne zu).
- Wert von Stufe 1 gegen deine Kurve (20 Paare, mittlerer Score-Unterschied; Hand real / Standardhand): OWN +0.57 / -0.90, CAP x0.5 +0.80 / -0.35, CAP x2.0 +1.61 / -0.38, TRANS x2.0 +0.84 / -0.96, natural +0.88 / -0.15, linear +1.13 / -0.96. Anteil Läufe mit Unterschied <= -2: 17-25 % (real) und 25-42 % (Standard). Das ist von Null nicht zu unterscheiden (Streuung eines 20-Paare-Mittels etwa ±1). **Stufe 1 schadet im Mittel nicht, ein Nutzen ist in der Simulation nicht nachweisbar.**

Was das für die Aussage der App heißt: Der Entwurf ist ein kleiner, begründeter Schritt in die Richtung deines Ziels, mit dem Hinweis "nicht als dein bester Wert gemessen". Er sagt nichts über eine Präferenz, die sich in 15 Runden nicht messen lässt. Ob ein Stufenplan (Schritt 4) über mehrere Sessions mit echten Runden mehr zeigt, ist der nächste Test.

Nicht umgesetzt in Schritt 5: die Gewichte aus Micro-Anteil und Konstanz (Schritt 6 definiert Konstanz erst), und der Export von Stufe 1 als gepatchte Kopie deiner Datei (Schritt 7). Der Blindtest-Ergebnisbildschirm kann bisher nur nach `ACCEL_BETTER` exportieren.

## 11. Stand Schritt 7 (Export von Stufe 1, Teil des Schritts)

Umgesetzt:
- `RawAccelExport.patchWithTable`: Kopie deines Profils unter dem Namen "<Profil> (AimTune)", die die Tabelle von Stufe 1 spielt. Nur der Modus-Block ändert sich (Modus `lut`, Form Velocity, `data` = die Tabellenpunkte); alle anderen Schlüssel des Profils und der Datei, auch dein Original-Profil, bleiben unverändert. Die Tabelle ist der Gesamt-Gain, den der Trainer gespielt hat; sie wird durch den Anteil Output-DPI/Geräte-DPI (bei dir 600/800) geteilt, die Output-DPI behält ihren Wert. Ein flacher Endpunkt bei 10000 in/s hält den letzten Wert wie im Trainer. Ein Profil nach Komponenten (zwei Kurven) wird abgelehnt.
- `DraftStage`, `PatchedCopy(stage = ...)`, `HubSession.rememberStage` (gehört zum importierten Profil, fällt beim Import eines anderen weg), `AppContext.showDraftExport` und der Knopf "Stufe 1 als Kopie meiner Datei speichern" auf der Entwurf-Karte. Der Export läuft über den vorhandenen Export-Bildschirm des Hubs (Speichern unter, Export-Ordner, Zwischenablage); die Änderung steht dort als "curve: classic -> table of N points: your curve with its rise at x1.12 (stage 1 of the draft)". Der Export braucht keinen bestandenen A/B (deine Entscheidung 3); der Bildschirm sagt weiter, dass nur die Änderungen aus dem Blindtest getestet sind.
- Tests: `RawAccelPatchTest` (+2: Tabelle wird so gespielt, wie sie getestet wurde, bis 1e-5; Name vergeben und Profil nach Komponenten werden abgelehnt), `PatchedCopyTest` (+1), `HubSessionTest` (+1).

**Nicht geprüft:** Die erzeugte Datei wurde nicht in Raw Accel selbst geladen (wie bei den bisherigen Exporten, siehe offene Punkte M13/M14/M17). Bitte vor dem Spielen in Raw Accel importieren und den Graphen mit der Karte vergleichen.

Offen aus Schritt 7: Rest der UI-Texte (Begründung auf der Ergebnisseite), Stufenplan-Anzeige (hängt an Schritt 4).

## Regeln dieser Session
Kein Code, keine Commits, kein Push, kein Merge nach `main`, `user-rawaccel-settings.json` und Datenbanken nur lesend. Der Nutzerprofil-Inhalt gehört in keinen Bericht.

## Verifikation (nach Freigabe)
- `./gradlew :core:test :app:test` nach jedem Schritt; Gates mit `AIMTUNE_REVIEW=1 ./gradlew.bat :core:test --tests "*FindCurveReference*" --rerun-tasks`.
- `./gradlew build` als Abschluss, `:app:screenshots` und `:app:screenshotsDe` für die UI.
- `docs/PROGRESS.md` am Ende jeder Task und vor jedem Commit aktualisieren.
