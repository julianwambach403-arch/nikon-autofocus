# Nikon AutoFocus – USB/PTP Schärfewächter für die Nikon D3400

Android-App, die eine Nikon-DSLR per **USB-OTG** ansteuert, den **LiveView-Strom über PTP**
empfängt, jedes Bild mit der **Varianz des Laplace-Operators** auf Schärfe misst und bei
bestätigter Unschärfe **genau einen** Autofokus-Befehl schickt – mit Glättung, Frame-Zähler
und Cooldown, damit kein Focus-Pumping entsteht.

Das Projekt ist ein vollständiges Gradle/Android-Studio-Projekt inklusive Gradle-Wrapper.
Es gibt keine Mock-Kamera und keinen Demo-Modus: die USB-Kommunikation ist eine echte,
eigene PTP-Implementierung.

---

## 1. Architektur

```
UsbPtpTransport        Bulk-Endpunkte, Interface belegen, Chunking, ZLP-Handling
      ▲
PtpSession             Container-Framing (Command / Data / Response), Transaktions-IDs
      ▲
NikonPtpCamera         Nikon-Vendor-Befehle, Capability-Erkennung, LiveView, AF-Drive
      ▲
UsbPtpManager          Geräteerkennung, USB-Berechtigung, Hotplug, EIN PTP-Thread
      ▲
MainViewModel  ──►  LiveViewProcessor  ──►  SharpnessAnalyzer  ──►  FocusStateMachine
                    (JPEG aus Payload)      (Grau + Laplace-Varianz)   (Anti-Pumping)
                                                                          │
                                                                    FocusController
                                                                    (sendet 0x90C1)
```

| Datei | Aufgabe |
|---|---|
| `usb/PtpConstants.kt` | Alle Opcodes, Response-Codes, Device-Properties |
| `usb/PtpTypes.kt` | Wire-Format, `DeviceInfo`-Parser, Exceptions |
| `usb/UsbPtpTransport.kt` | Rohe Bulk-Übertragung |
| `usb/PtpSession.kt` | PTP-Transaktionen |
| `usb/NikonPtpCamera.kt` | Nikon-Befehle + Fähigkeitsmodell |
| `usb/UsbPtpManager.kt` | USB-Lebenszyklus, ein serieller I/O-Thread |
| `usb/CameraError.kt` | Fehlerkatalog mit deutschen Klartextmeldungen |
| `liveview/JpegExtractor.kt` | JPEG aus dem Nikon-LiveView-Header schneiden (pure Kotlin) |
| `liveview/LiveViewProcessor.kt` | Dekodierung zu `Bitmap` |
| `analysis/LaplacianVariance.kt` | `score = variance(Laplacian(gray))` |
| `analysis/SharpnessAnalyzer.kt` | Bitmap → Graustufen (box-downscale) → Score |
| `analysis/MovingAverage.kt` | Gleitender Mittelwert über N Werte |
| `focus/FocusStateMachine.kt` | Zustandsautomat, Anti-Pumping |
| `focus/FocusController.kt` | AF-Befehl + Policy (nicht unterstützt / busy) |
| `focus/FocusSettings.kt` | Einstellbare Parameter mit Grenzen |
| `settings/SettingsRepository.kt` | Persistenz (SharedPreferences) |
| `ui/*` | Jetpack Compose (Material 3) |

**Warum kein OpenCV?** Der Laplace-Filter läuft auf einem auf ~320 px herunterskalierten
Graustufenbild – das sind rund 68.000 Pixel und vier Additionen pro Pixel. Eine
handgeschriebene Schleife über ein `IntArray` braucht dafür einstellige Millisekunden. Die
rund 10 MB nativen OpenCV-Bibliotheken würden die APK aufblähen, ohne die Pipeline
schneller zu machen – der Flaschenhals ist der USB-Roundtrip, nicht die Rechnung.

---

## 2. Welche Android-Version wird benötigt?

* **Minimum: Android 8.0 (API 26).**
* **Ziel/getestet gegen: Android 15 (API 35).**
* Erforderliches Feature: `android.hardware.usb.host` (im Manifest als `required="true"`
  deklariert – Geräte ohne USB-Host-Modus können die App gar nicht erst installieren).
* Keine Root-Rechte nötig. Die App benutzt ausschließlich die öffentliche
  `android.hardware.usb`-Host-API; es gibt keine versteckten Systemzugriffe.
* Ab Android 14 gilt die strengere Broadcast-Registrierung: die App fordert die
  USB-Berechtigung mit einem explizit auf das eigene Paket adressierten `PendingIntent`
  (`FLAG_IMMUTABLE`) und einem `RECEIVER_NOT_EXPORTED`-Receiver an. Das ist bereits
  implementiert.

---

## 3. Welche USB-OTG-Hardware wird benötigt?

1. **Ein Android-Telefon/-Tablet mit USB-Host-Unterstützung (OTG).** Das ist keine
   Selbstverständlichkeit: manche Einsteigergeräte haben den Host-Modus deaktiviert. Prüfen
   lässt es sich mit jeder „USB OTG Checker"-App oder daran, ob ein USB-Stick am Adapter
   erkannt wird.
2. **Ein OTG-Adapter oder -Kabel** passend zum Telefonanschluss:
   * USB-C-Telefon → `USB-C (Stecker) auf USB-A (Buchse)` OTG-Adapter, oder direkt ein
     `USB-C auf Micro-USB-B`-Kabel.
   * Micro-USB-Telefon → `Micro-USB-OTG-Adapter` (Pin 4 muss auf Masse liegen; reine
     Ladekabel funktionieren **nicht**).
3. **Das Kamerakabel:** Die D3400 hat eine **Micro-USB-B-Buchse (Micro-B, 5-polig)**. Das
   mitgelieferte Nikon-UC-E20-Kabel ist `USB-A → Micro-B`.
4. **Empfohlen: ein OTG-Hub mit externer Stromversorgung (Y-Kabel / powered hub).**
   LiveView über USB zieht dauerhaft Strom aus dem Telefon und lädt es gleichzeitig nicht.
   Für Sessions über ein paar Minuten hinaus ist eine Einspeisung praktisch Pflicht.
5. **Der Kamera-Akku muss geladen sein.** Ein schwacher Akku ist einer der Gründe, aus
   denen die Kamera LiveView per `LiveViewProhibitCondition` verweigert – die App zeigt
   diesen Grund im Klartext an.

---

## 4. Wie wird die Nikon D3400 angeschlossen?

1. Kamera **ausschalten**.
2. Objektiv auf **AF** stellen (Schalter am Objektiv, bei AF-P-Objektiven im Menü). Steht
   das Objektiv auf **M**, quittiert die Kamera `Nikon_AfDrive` mit `Nikon_InvalidStatus` –
   die App meldet das, kann aber nichts dagegen tun.
3. Fokusmodus in der Kamera auf **AF-S** (Einzelautofokus). AF-C würde die Kamera
   ohnehin dauernd nachführen und macht die App überflüssig.
4. Speicherkarte einlegen (manche Firmware-Stände verweigern LiveView ohne Karte).
5. **Menü → Einstellungen → USB (bzw. „Verbinden mit Smart-Gerät" deaktivieren)**. Die
   Kamera muss im **MTP/PTP**-Modus arbeiten, nicht im Massenspeichermodus und nicht in
   einem SnapBridge-Bluetooth-Modus.
6. Kabel: `Kamera Micro-B` → `USB-A` → `OTG-Adapter` → `Telefon`.
7. Kamera **einschalten**. Android meldet in aller Regel sofort „Nikon AutoFocus für dieses
   USB-Gerät öffnen?" – das ist der im Manifest hinterlegte
   `USB_DEVICE_ATTACHED`-Intent-Filter.
8. **LiveView an der Kamera aktivieren** (Lv-Hebel/Taste), damit der Spiegel hochklappt und
   der Sensor ein Bild liefert. Bei vielen Bodies startet `Nikon_StartLiveView` das auch
   ferngesteuert – wenn nicht, ist der Hebel der zuverlässigere Weg.

---

## 5. Wie wird die APK gebaut?

> **Status: gebaut und verifiziert.** Debug- und Release-APK wurden mit dieser
> Konfiguration erfolgreich erzeugt (AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.9 / JDK 17,
> compileSdk 35), 14 von 14 Unit-Tests grün, keine Compiler-Warnungen.
>
> | Artefakt | Pfad | Größe |
> |---|---|---|
> | Debug | `app/build/outputs/apk/debug/app-debug.apk` | ~9,2 MB |
> | Release | `app/build/outputs/apk/release/app-release.apk` | ~6,3 MB |
>
> Beide sind mit dem Debug-Schlüssel signiert (APK Signature Scheme v2) und direkt
> installierbar.

### Voraussetzungen

* **JDK 17** (AGP 8.7 verlangt 17; JDK 21 geht auch, JDK 25 **nicht**).
* **Android SDK** mit Platform **API 35**, Build-Tools **35.0.0** und **34.0.0**
  (34.0.0 zieht sich AGP beim ersten Build selbst nach, wenn eine Internetverbindung
  besteht).
* Gradle wird vom mitgelieferten Wrapper (`gradle-8.9`) automatisch geladen.

### Variante A – Android Studio (einfachster Weg)

1. Android Studio (Ladybug oder neuer) öffnen → **Open** → Ordner `NikonAutoFocus` wählen.
2. Studio lädt Gradle und das SDK selbstständig nach.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`.

### Variante B – Kommandozeile

`local.properties` im Projektwurzelverzeichnis anlegen (falls `ANDROID_HOME` nicht gesetzt
ist):

```properties
sdk.dir=C\:\\Users\\<name>\\AppData\\Local\\Android\\Sdk
```

Auf diesem Rechner liegt eine fertige Toolchain unter `D:\Apks\toolchain` (JDK 17 +
Android SDK); die passende `local.properties` ist bereits angelegt. Zum erneuten Bauen:

```bash
JAVA_HOME=/d/Apks/toolchain/jdk17 ANDROID_HOME=/d/Apks/toolchain/android-sdk ./gradlew assembleDebug
```

Dann:

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

* Debug-APK: `app/build/outputs/apk/debug/app-debug.apk`
* Release-APK: `app/build/outputs/apk/release/app-release.apk`

Die Release-Variante wird **ohne eigenen Keystore mit dem Debug-Schlüssel signiert**, ist
also direkt installierbar. Für einen eigenen Schlüssel eine `keystore.properties` neben
`settings.gradle.kts` legen:

```properties
storeFile=../release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

### Variante C – ohne lokales SDK, per GitHub Actions

`.github/workflows/build.yml` baut Debug- und Release-APK und lädt beide als Artefakt hoch.
Repository zu GitHub pushen, Workflow „Build APK" starten, APK herunterladen.

### Installieren

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Oder die APK-Datei aufs Telefon kopieren und „Installation aus unbekannten Quellen"
erlauben.

### Tests

```bash
./gradlew testDebugUnitTest
```

Die Unit-Tests decken den Zustandsautomaten (genau ein AF-Trigger pro Unschärfe-Episode,
Cooldown, Zurücksetzen des Zählers), den gleitenden Mittelwert, die Laplace-Varianz und die
JPEG-Extraktion aus dem LiveView-Header ab.

---

## 6. Wie wird die Kamera in der App verbunden?

1. App starten (oder sie öffnet sich beim Einstecken von selbst).
2. Die Statuszeile zeigt an, ob ein PTP-Gerät gefunden wurde.
3. **„Verbinden"** antippen.
   * Android fragt einmalig nach der USB-Berechtigung → **Zulassen**.
   * Die App belegt das Still-Image-Interface (Klasse 6/1/1) mit `force=true` und entzieht
     es damit dem Android-MTP-Dienst.
   * Sie liest `GetDeviceInfo`, öffnet eine PTP-Session und liest `DeviceInfo` erneut
     (manche Bodies melden den vollen Befehlssatz erst innerhalb einer Session).
   * `Nikon_StartLiveView` wird nur gesendet, wenn der Body den Opcode überhaupt meldet.
4. Unter **„PTP-Diagnose → Anzeigen"** steht schwarz auf weiß, was die angeschlossene
   Kamera kann – inklusive der vollständigen Opcode-Liste. Das ist der Punkt, an dem sich
   klären lässt, ob die eigene D3400 LiveView und AF-Drive beherrscht.
5. **„Überwachung START"** aktiviert die Automatik. „Jetzt fokussieren" löst einmalig
   manuell aus.

### Schwellwert kalibrieren

Der Score ist **kein absoluter Wert** – er hängt vom Motiv, vom Kontrast, von der
LiveView-Auflösung der Kamera und von der eingestellten Analyse-Auflösung ab.
Vorgehen:

1. Verbinden, Überwachung **aus** lassen.
2. Scharf stellen → den angezeigten geglätteten Wert notieren (z. B. 1800).
3. Manuell defokussieren → Wert notieren (z. B. 200).
4. Schwellwert etwa auf ein Drittel bis die Hälfte dazwischen setzen (hier: ~600).
5. Wird die Analyse-Auflösung später geändert, **muss neu kalibriert werden** – die App
   weist im Einstellungsdialog darauf hin.

---

## 7. Welche PTP-Befehle werden für den Autofokus verwendet?

Alle Werte stammen aus dem Nikon-Vendor-Opcode-Bereich, wie er in `libgphoto2`
(`camlibs/ptp2/ptp.h`) dokumentiert ist.

| Opcode | Name | Rolle in dieser App |
|---|---|---|
| `0x90C1` | `Nikon_AfDrive` | **Der Autofokus-Befehl.** Keine Parameter, keine Datenphase. Entspricht dem Halbdruck des Auslösers bzw. AF-ON und löst *nicht* aus. |
| `0x90C8` | `Nikon_DeviceReady` | Bereitschaftsprüfung. `RC_OK` = idle, `0x2019 DeviceBusy` = beschäftigt. Wird **vor** jedem AF-Befehl einmal und **danach** in 10-ms-Schritten abgefragt, bis die Kamera fertig ist. |
| `0x9201` | `Nikon_StartLiveView` | LiveView starten |
| `0x9203` | `Nikon_GetLiveViewImg` | LiveView-Frame holen (Datenphase) |
| `0x9202` | `Nikon_EndLiveView` | LiveView beenden |
| `0x9200` | `Nikon_GetPreviewImg` | **Fallback**, wenn der Body keinen LiveView meldet |
| `0x9206` | `Nikon_AfDriveCancel` | AF abbrechen beim Stoppen der Überwachung |
| `0x90C2` | `Nikon_ChangeCameraMode` | Fernsteuerung übernehmen (optional, Fehler wird toleriert) |
| `0xD1A2` | `LiveViewStatus` (Property) | Läuft LiveView bereits? |
| `0xD1A4` | `LiveViewProhibitCondition` (Property) | Grund, warum LiveView verweigert wird – wird im Klartext angezeigt |
| `0xD10B` | `RecordingMedia` (Property) | Vor LiveView auf SDRAM gesetzt |

### Auslöser für Foto und Video

| Opcode | Name | Rolle |
|---|---|---|
| `0x9207` | `Nikon_InitiateCaptureRecInMedia` | **Fotoauslöser, bevorzugt.** 2 Parameter: `(af, ziel)`. `af` = `0xFFFFFFFF` (ohne AF) oder `0xFFFFFFFE` (mit AF), `ziel` = 0 Speicherkarte / 1 SDRAM. Bodies, die nur einen Parameter akzeptieren, antworten `ParameterNotSupported` und werden automatisch auf die 1-Parameter-Form umgestellt. |
| `0x90CB` | `Nikon_AfCaptureSDRAM` | Fallback, nur außerhalb LiveView (fokussiert selbst) |
| `0x90C0` | `Nikon_InitiateCaptureRecInSdram` | Fallback |
| `0x100E` | `InitiateCapture` (Standard-PTP) | letzter Fallback, Parameter `(0, 0)` |
| `0x920A` | `Nikon_StartMovieRecInCard` | **Videoaufnahme starten** (auf die Speicherkarte) |
| `0x920B` | `Nikon_EndMovieRec` | **Videoaufnahme beenden** |
| `0x90C7` | `Nikon_GetEvent` | Event-Queue nach jeder Aufnahme leeren, damit die Kamera nicht blockiert |
| `0xD1F0` | `ApplicationMode` (Property) | Vor 0x920A auf 1 gesetzt (D3400 hat sie, Default 0) |
| `0xD0A4` | `MovRecProhibitCondition` (Property) | Nach einem fehlgeschlagenen Videostart ausgelesen, nie zum Überspringen von 0x920A |
| `0xD1A6` | `LiveViewSelector` (Property) | 0 Foto-LiveView, 1 Video-LiveView; vor 0x920A auf 1 gesetzt |
| `0xD10B` | `RecordingMedia` (Property) | Vor Foto **und** Videostart auf Speicherkarte gesetzt (0x920A = roter Knopf) |

### LiveView-Kamerasteuerung (Camera Connect & Control)

Die App übernimmt die **guten Aspekte** der Fernsteuerungs-UI von *Camera Connect & Control* – LiveView als Arbeitsfläche, Belichtung direkt am Bild, Histogramm, Gitter, großer Auslöser – **ohne** deren APK oder Code zu verwenden. Alles läuft über die vorhandene PTP-Schicht und nur über Properties, die `DeviceInfo` der angeschlossenen Kamera wirklich auflistet.

| Anzeige | PTP-Property | Anmerkung |
|---|---|---|
| Belichtungsmodus P/S/A/M | `0x500E` ExposureProgramMode | Chip „P/S/A/M/AUTO“ |
| Verschlusszeit | `0x500D` ExposureTime | Wert / 10 000 s |
| Blende | `0x5007` FNumber | Wert / 100 |
| ISO | `0x500F` ExposureIndex | |
| Belichtungskorrektur | `0x5010` ExposureBiasCompensation | INT16, 1/1000 EV |
| Weißabgleich | `0x5005` WhiteBalance | |
| Messung / Antrieb / Qualität / Blitz | `0x500B` / `0x5013` / `0x5004` / `0x500C` | |
| Akku | `0x5001` BatteryLevel | HUD oben links |
| Restbilder | `GetStorageInfo` `0x1005` | HUD oben links |

Zusätzlich: **Drittelregel-Gitter**, **Luma-Histogramm** (64 Klassen aus dem Analysebild), **Pinch-Zoom** im LiveView (Doppel-Tipp setzt zurück) und eine **Auslöserschiene** (AF / Foto / REC) am rechten Bildrand. Fehlt eine Property am Body, bleibt der Chip einfach weg – die D3400 bietet nicht alles, höhere Bodies oft mehr.

**Wichtig – Autofokus beim Auslösen:** Läuft LiveView, ist der Spiegel oben und der
Phasen-AF kann nicht arbeiten. Die App löst deshalb im LiveView grundsätzlich **ohne** AF
aus (`0xFFFFFFFF`); die Kamera würde sonst `InvalidStatus` melden. Scharfgestellt wird
vorher über `Nikon_AfDrive` – also genau über die Fokusüberwachung oder den Knopf
„Jetzt fokussieren".

**Wichtig – Speicherziel:** Die LiveView-Startsequenz setzt `RecordingMedia` auf SDRAM
(so macht es auch libgphoto2). Würde man danach ohne Korrektur auslösen, landete das Foto
im flüchtigen Kameraspeicher oder die Kamera meldete `StoreNotAvailable`. Die App setzt
`RecordingMedia` deshalb **vor jedem Auslösen** explizit auf das in den Einstellungen
gewählte Ziel (Standard: Speicherkarte).

**Ausdrücklich nicht verwendet:**

* `0x100E InitiateCapture` und `0x90C0 InitiateCaptureRecInSdram` – die würden **auslösen**,
  nicht fokussieren.
* `0x9206` ist entgegen einer verbreiteten Verwechslung **kein** AF-Trigger, sondern
  `AfDriveCancel`.

Ein separates „press_shutter_half" gibt es im Nikon-PTP-Protokoll nicht. `Nikon_AfDrive`
**ist** der Halbdruck. Antwortet die Kamera darauf mit `0x2005 OperationNotSupported`,
setzt die App ein permanentes Flag, schaltet die Überwachung ab und sendet den Befehl
**nie wieder** – genau wie gefordert.

### Antwortcodes, die ausgewertet werden

| Code | Bedeutung | Reaktion |
|---|---|---|
| `0x2001 OK` | AF gestartet | auf `DeviceReady` warten → „Fokus gefunden" |
| `0xA002 Nikon_OutOfFocus` | AF lief, konnte nicht scharfstellen | zählt als Versuch, Cooldown startet, Warnhinweis |
| `0x2019 DeviceBusy` | Kamera beschäftigt | **kein** Versuch gezählt, Cooldown trotzdem; nach 3× Warnung „läuft eine Videoaufnahme?" |
| `0x2005 OperationNotSupported` | Body kann das nicht | Automatik dauerhaft aus |
| `0xA00B Nikon_NotLiveView` | LiveView beendet | Fehlermeldung, Automatik stoppt |
| `0xA004 Nikon_InvalidStatus` | falscher Zustand (z. B. Objektiv auf M) | wie „busy" behandelt |

---

## 8. Was hängt von der konkreten D3400-Firmware ab?

Dieser Abschnitt ist bewusst offen formuliert, weil er der eine Punkt ist, der sich ohne
das konkrete Gerät nicht abschließend klären lässt.

1. **Ob die D3400 `Nikon_StartLiveView` / `Nikon_GetLiveViewImg` überhaupt meldet.**
   Die D3400 ist ein Einsteiger-Body, bei dem Nikon den Fernsteuerungsumfang gegenüber
   D5xxx/D7xxx deutlich beschnitten hat. In den `libgphoto2`-Berichten zur D3400
   (Firmware V1.10) schlägt schon `--capture-image` mit *„PTP Store Not Available"* fehl.
   Ob LiveView per PTP läuft, hängt am Firmware-Stand und ist nicht garantiert.
   **Deshalb rät die App nicht, sondern fragt:** sie liest `DeviceInfo.OperationsSupported`
   und zeigt jeden relevanten Opcode mit „ja"/„NEIN" an. Fehlt LiveView, schaltet sie
   automatisch auf `Nikon_GetPreviewImg` (0x9200) um – niedrigere Bildrate, identische
   Schärfeanalyse. Fehlt auch das, meldet sie „LiveView nicht verfügbar" statt ins Leere zu
   pollen.
2. **Ob `Nikon_AfDrive` (0x90C1) akzeptiert wird.** Gleiche Logik: Opcode-Liste entscheidet,
   und ein `OperationNotSupported` zur Laufzeit schaltet die Automatik permanent ab.
3. **Die Größe und das Layout des LiveView-Headers.** Nikon stellt dem JPEG einen
   Vendor-Header voran, der je nach Body und Firmware 8, 128 oder 384 Byte groß ist.
   Die App verlässt sich deshalb **nicht** auf eine feste Länge, sondern wertet zuerst das
   führende 32-Bit-Offsetfeld aus und sucht sonst die JPEG-Marker `FF D8` / `FF D9` – der
   Weg, den `libgphoto2` ebenfalls geht und der Firmware-Änderungen übersteht.
4. **Die Bitbelegung von `LiveViewProhibitCondition` (0xD1A4).** Die Bedeutung der Bits ist
   von Nikon nicht öffentlich dokumentiert; die App nutzt die Zuordnung aus `libgphoto2`
   und zeigt **zusätzlich immer den Rohwert in Hex** an, damit auch ein unbekanntes Bit
   nachvollziehbar bleibt.
5. **Ob `ChangeCameraMode` (0x90C2) unterstützt wird.** Wird nur versucht, wenn gemeldet;
   ein Fehlschlag ist nicht fatal.
6. **Verhalten während interner Videoaufzeichnung.** Dass die D3400 externe Fokusbefehle
   während einer laufenden SD-Aufzeichnung blockiert, äußert sich als `DeviceBusy` – die
   App erkennt das an der Häufung und benennt die wahrscheinliche Ursache.

---

## 7a. AF-Rahmen im Bild und Fokusmodi

### Der Rahmen

Die Position des AF-Messfelds steht im **Vendor-Header, den Nikon dem LiveView-JPEG
voranstellt**. Der Header ist – anders als PTP selbst – **Big-Endian**. Es gibt **zwei
Layouts** mit identischer Feldreihenfolge; das neuere hat schlicht acht Byte mehr davor.
digiCamControl nutzt das klassische für D90/D5000/D7000/D5100 (`NikonBase`) und das
erweiterte für D600/D800/D5200/D5300/D5500/D5600/D3300 und **D3400** (`NikonD600Base`):

| klassisch | erweitert (+8) | Typ | Feld |
|---|---|---|---|
| 0 | 8 | u16 | Breite des LiveView-JPEGs |
| 2 | 10 | u16 | Höhe des LiveView-JPEGs |
| 4 | 12 | u16 | Breite des Gesamtbilds = **Koordinatenraum des AF-Felds und von `ChangeAfArea`** |
| 6 | 14 | u16 | Höhe des Gesamtbilds |
| 16 | 24 | u16 | Breite des AF-Rahmens |
| 18 | 26 | u16 | Höhe des AF-Rahmens |
| 20 | 28 | u16 | Mittelpunkt X des AF-Rahmens |
| 22 | 30 | u16 | Mittelpunkt Y des AF-Rahmens |
| 29 | 37 | u8 | Rotation (1 = −90°, 2 = +90°) |
| 40 | 48 | u8 | Fokusstatus (**1 = nicht scharf**) |
| 60 | 68 | u8 | Videoaufnahme läuft |

Die App zeichnet daraus einen Rahmen mit Eckwinkeln über das Vorschaubild:
**grün = Kamera meldet Fokus, gelb = kein Fokus**.

**Warum das nicht blind übernommen wird:** Die Headerlänge schwankt je nach Body und
Firmware (8, 64, 128, 384 Byte), und bei einem anderen Layout wären die gelesenen Zahlen
Unsinn. Ein selbstbewusst an der falschen Stelle gezeichneter AF-Rahmen ist schlechter als
gar keiner. Deshalb prüft der Parser jeden Header, bevor er ihm glaubt – die stärkste
Prüfung ist, dass **die LiveView-Breite/Höhe im Header exakt zum dekodierten JPEG passen
muss**. Bei 384-Byte-Headern wird zuerst das erweiterte Layout probiert, sonst das
klassische; das jeweils andere ist der Fallback. Zusätzlich müssen Rahmengröße und
Mittelpunkt innerhalb des Bilds liegen. Schlägt alles fehl, wird schlicht kein Rahmen
gezeichnet. Die Diagnose-Seite zeigt unter „AF-Feld / LiveView-Header", welches Layout
erkannt wurde. Unit-Tests decken beide Layouts ab.

Die Zeichenfläche berücksichtigt außerdem, dass das Bild mit `ContentScale.Fit` zentriert
dargestellt wird: Der Rahmen wird gegen das **tatsächlich gezeichnete Bildrechteck**
positioniert, nicht gegen den Container. Sonst säße er bei jedem Seitenverhältnis ≠ 3:2
falsch.

### Eigenes Fokusfeld (wenn die Kamera nichts meldet)

Die Karte „Fokus" sagt immer, **woher die Anzeige kommt** – das ist der Punkt, an dem sonst
Ratlosigkeit entsteht:

| Anzeige | Bedeutung |
|---|---|
| 🟢 **Fokus auf: AF-Feld der Kamera** | Grüner Rahmen mit Eckwinkeln. Kommt aus dem LiveView-Header – genau darauf stellt die Kamera scharf. |
| 🔵 **Fokus auf: eigenes Fokusfeld** | Blauer Rahmen ohne Ecken. Die Kamera meldet kein AF-Feld; das ist das Feld, in dem die App misst und das sie der Kamera per `ChangeAfArea` schickt. |
| 🟡 **Fokus auf: ganzes Bild** | Kein Feld gesetzt, Schärfe wird über das komplette Bild gemittelt – ein unruhiger Hintergrund zählt dann genauso viel wie das Motiv. |

Das eigene Fokusfeld wird per Schalter aktiviert, mit einem Tipp ins Vorschaubild platziert
und über einen Regler in der Größe (8 – 100 % der Bildbreite) verändert. Es ist **quadratisch
in Pixeln**, nicht in Bildanteilen – die Höhe wird aus dem Seitenverhältnis abgeleitet, sonst
wäre das „Quadrat" auf einem 3:2-Bild verzerrt.

Ist es aktiv, liest [SharpnessAnalyzer](app/src/main/java/de/nikonautofocus/app/analysis/SharpnessAnalyzer.kt)
per `getPixels` nur noch diesen Ausschnitt. Das ist zugleich schneller und trennschärfer.
**Der Schwellwert muss danach neu eingestellt werden** – ein kleineres Feld liefert eine
andere Varianz-Skala. Die App weist im UI darauf hin.

### AF-Messfeld verschieben

Tippen oder Ziehen im Vorschaubild sendet `Nikon_ChangeAfArea` (0x9205) mit Koordinaten im
**Gesamtbild-Raster aus dem LiveView-Header** (klassisch Offset 4/6, erweitert 12/14) –
genau wie digiCamControl (`LiveViewViewModel.SetFocusPos` → `NikonBase.Focus(x, y)` skaliert
mit `ImageWidth/ImageHeight`). Vor jedem `AfDrive` mit aktivem eigenem Fokusfeld passiert
dasselbe: erst ChangeAfArea auf die Feldmitte, bei Gesichtserkennung/Motivverfolgung
zusätzlich Umschalten auf Spot bzw. Normal (0xD05D), dann AfDrive.

**Warum es vorher immer oben links scharf wurde:** Die D3400 liefert den erweiterten
Header. Mit dem klassischen Layout gelesen stimmten JPEG-Breite/Höhe an Offset 0/2 nicht,
also gab es keinen erkannten Header – und die App schickte Koordinaten im 640-px-JPEG-Raster
(oder im geratenen Gesamtbild). Für ein Gesamtbild von mehreren tausend Pixeln liegt
`(320, 212)` in der linken oberen Ecke; dort blieb das Messfeld.

**Kontrolle statt Vertrauen:** Nach jedem ChangeAfArea vergleicht die App den im nächsten
Header gemeldeten AF-Mittelpunkt mit der angeforderten Position. Weicht er um mehr als
Feld-/Rahmenhälfte plus 6 % ab, erscheint eine Warnung mit beiden Positionen; das Ergebnis
steht außerdem in der Diagnose. Wird der Header gar nicht erkannt, sendet die App **kein**
ChangeAfArea (ein geratenes Raster ist schlimmer als keins), warnt einmal und lässt den
`AfDrive` auf dem Messfeld laufen, das die Kamera gerade hat – ein normaler Autofokus.

**Belichtungsprogramm:** Weder AfDrive noch ChangeAfArea noch 0xD05D hängen am Moduswahlrad;
P, S, A und M verhalten sich identisch. Nikon sperrt die Fernsteuerung nur in AUTO, GUIDE,
EFFECTS und den Motivprogrammen. Die Diagnose zeigt den zuletzt gelesenen Wert von 0x500E
neben dem AF-Feld-Ergebnis, damit ein Zusammenhang sichtbar wäre, falls es doch einen gibt.

### Wenn der Videostart mit `Nikon_InvalidStatus` abgelehnt wird

`0xA004 Nikon_InvalidStatus` ist Nikons Sammelcode für „falscher Zustand" und sagt für sich
genommen nichts Verwertbares. Die App arbeitet erst die Sequenz ab, die in
[digiCamControl](https://github.com/dukus/digiCamControl) (`NikonBase.StartRecordMovie`)
und libgphoto2 (`_put_Nikon_Movie`) steht, und liest danach den Kamerazustand aus:

**Voraussetzung – `Nikon_GetVendorPropCodes` (0x90CA):** Nikon-Bodies führen ihre
0xD0xx/0xD1xx-Properties **nicht** in `DeviceInfo` auf. Die D3400 meldet dort nur die
PIMA-Standardwerte (0x5001, 0x500D, 0x500E …); `LiveViewStatus`, `RecordingMedia`,
`ApplicationMode`, `MovRecProhibitCondition` und die AF-Properties erscheinen erst über
0x90CA. Die App fragt den Opcode direkt nach `OpenSession` ab und mischt die Liste in die
Geräteinfo (wie libgphoto2 in `fixup_cached_deviceinfo`). Ohne diesen Schritt zeigen alle
Nikon-Properties in der Diagnose „n/v" und jeder property-gesteuerte Pfad – auch die
Videostart-Sequenz unten – bleibt stumm.

**Startsequenz (libgphoto2 `_put_Nikon_Movie` + Camera Connect and Control):**

1. **Application-Modus an – bei ausgeschaltetem LiveView.** Die D3400 hat Property
   `0xD1F0` und lässt sie auf 0. Nikon übernimmt den Wert nur, solange LiveView **nicht**
   läuft; eine bereits laufende Foto-LiveView-Sitzung kann 0x920A nicht starten. Steht
   0xD1F0 beim Druck auf REC noch auf 0, beendet die App deshalb LiveView (PC-Steuerung
   bleibt), schreibt 1, sendet `0x9435` (falls gemeldet) und startet LiveView neu.
2. **`RecordingMedia` (0xD10B) auf Speicherkarte.** 0x920A ist `StartMovieRecInCard` –
   dasselbe wie der rote Knopf am Gehäuse. Der Neustart aus Schritt 1 lässt SDRAM bewusst
   aus; dagegen antwortet die D3400 mit `InvalidStatus`.
3. **LiveView muss laufen** (`LiveViewStatus` 0xD1A2), Events werden geleert.
4. **`DeviceReady`, dann `0x920A` ohne Parameter und ohne Datenphase**
   (`ptp_generic_no_data(..., StartMovieRecInCard, 0)`). Das Video landet auf der SD-Karte.
5. Bei `InvalidStatus`/`NotLiveView`: einmal LiveView im Application-Modus neu starten
   (Schritt 1) und 0x920A wiederholen. Nur wenn `LiveViewSelector` (0xD1A6) existiert,
   zusätzlich auf Video-LiveView umschalten. Die D3400 hat diese Property nicht.
6. **Fallback:** `InitiateOpenCapture` (0x101C).

Der Application-Modus bleibt bis zum Ende der LiveView-Sitzung aktiv (`endLiveView` setzt
0xD1F0 zurück), damit weitere Aufnahmen keinen erneuten LiveView-Neustart brauchen.

**Wenn es dann immer noch scheitert**, zeigt die App keine Vermutung mehr, sondern die
tatsächlichen Werte – und die PTP-Diagnose klappt dafür von selbst auf:

```
Antwort auf Videostart: Nikon_InvalidStatus
StartMovieRecInCard 0x920A: ja
InitiateOpenCapture 0x101C: NEIN
LiveViewStatus       0xD1A2: an (1)
MovRecProhibit       0xD0A4: n/v
ExposureProgram      0x500E: AUTO
RecordingMedia       0xD10B: SDRAM (intern)
LiveViewSelector     0xD1A6: Foto-LiveView (0)
...
```

Daraus benennt die App die wahrscheinlichste Ursache, sofern sich eine ableiten lässt –
z. B. „Moduswahlrad steht auf AUTO. Nikon erlaubt Fernsteuerung in der Regel nur in P, S, A
oder M." **Erste Dinge zum Ausprobieren:** Moduswahlrad auf **M, A, S oder P** (nicht AUTO,
GUIDE oder Motivprogramm), LiveView an der Kamera aktiv, Speicherkarte eingelegt und nicht
schreibgeschützt.

Meldet die Kamera dagegen gar keine Sperrgründe, ist das ebenfalls eine Aussage: viele
Einsteiger-Bodies führen `0x920A` zwar in der Operationsliste, erlauben den Videostart per
USB aber schlicht nicht. Dann bleibt der Weg über **„Kamera-Bedienung freigeben"** und die
rote Taste am Body.

> `LiveViewMode` (0xD1A0) und `MovieCaptureMode` (0xD304) werden nur gelesen. `LiveViewSelector`
> (0xD1A6) wird vor dem Videostart auf 1 geschrieben, wenn die Kamera die Property anbietet.

### Fokusmodi umschalten

| Property | Werte |
|---|---|
| `0xD05D` `LiveViewAFArea` | 0 Gesichtserkennung, 1 Großes Messfeld, 2 Normales Messfeld, **3 Motivverfolgung**, 4 Spot-Messfeld |
| `0xD061` `LiveViewAFFocus` | 0 AF-S, 1 AF-C, 2 AF-F (permanent), 3/4 MF |

**Motivverfolgung** ist `LiveViewAFArea = 3`.

Die angebotenen Modi kommen **nicht aus einer fest verdrahteten Liste**, sondern aus
`GetDevicePropDesc` (0x1014) der angeschlossenen Kamera. Dadurch kann die App keinen Wert
anbieten, den der Body ablehnen würde. Meldet die Kamera keine Aufzählung, werden die
dokumentierten Nikon-Werte als Rückfall angeboten. Nach jedem Schreiben wird der Wert
**zurückgelesen** statt angenommen – manche Bodies ignorieren oder begrenzen die Änderung
stillschweigend.

Fehlen `0xD05D` und `0xD061` in der Eigenschaftsliste, sagt die App das im Klartext; die
Messfeldsteuerung muss dann am Kamerabody eingestellt werden.

---

## 8a. Warum die Tasten an der Kamera nichts tun – und was die App dagegen macht

Das ist kein Fehler der App, sondern wie Nikon Tethering umsetzt: **solange ein Host die
PC-Steuerung hält und LiveView über USB läuft, sperrt die Kamera ihre eigenen
Bedienelemente.** Auslöser und die rote Video-Taste am Body reagieren dann nicht.

Zwei Auslöser dafür:

1. `Nikon_ChangeCameraMode(1)` (0x90C2) – die App übernimmt die Fernsteuerung. Das erwarten
   die meisten Nikon-Bodies für Tethering.
2. Der tethered LiveView-Betrieb selbst.

Die App bietet beides an:

* **Auslösen in der App.** Der Block „Aufnahme" hat einen **FOTO**- und einen
  **VIDEO START/STOPP**-Knopf mit laufender Aufnahmezeit und rotem REC-Indikator im
  Vorschaubild. Das ist der empfohlene Weg – die Kamera bleibt tethered, LiveView und
  Fokusüberwachung laufen weiter.
* **Kamera-Bedienung freigeben.** Der Schalter im selben Block beendet LiveView und sendet
  `Nikon_ChangeCameraMode(0)`. Danach funktionieren Auslöser und Video-Taste am Body
  wieder. Der Preis steht direkt daneben: **kein LiveView und keine Fokusüberwachung**,
  solange die Freigabe aktiv ist. Ein Umlegen des Schalters holt beides zurück.
* **PC-Steuerung gar nicht erst übernehmen.** In den Einstellungen lässt sich
  „PC-Steuerung übernehmen" abschalten, dann sendet die App `ChangeCameraMode` nie. Manche
  Bodies verweigern danach LiveView – deshalb ist die Option standardmäßig an. Sie wirkt
  erst beim nächsten LiveView-Start.

### Zusammenspiel mit der Fokusüberwachung

* Jede Aufnahme unterbricht den LiveView-Strom. Die App meldet das dem Zustandsautomaten
  (`onCameraInterruption`): das Mittelwertfenster wird verworfen und ein Cooldown startet.
  Ohne das würden die schwarzen Frames direkt nach dem Auslösen als „unscharf" gemessen und
  einen Autofokus auslösen, den niemand wollte. Zwei Unit-Tests decken genau das ab.
* Bleibt die Kamera nach einer Aufnahme außerhalb LiveView, startet die App LiveView
  einmalig automatisch neu, statt mit `Nikon_NotLiveView` abzubrechen.
* Während einer **internen Videoaufzeichnung** weist die D3400 Fokusbefehle ab. Die App
  pausiert die Überwachung deshalb standardmäßig für die Dauer der Aufnahme und nimmt sie
  danach von selbst wieder auf (abschaltbar in den Einstellungen).
* Während einer laufenden Videoaufnahme ist der Foto-Auslöser gesperrt.

---

## 9. Bekannte Einschränkungen

* **Latenz.** Der USB-LiveView-Strom liegt je nach Body und Telefon bei ~5–15 fps mit
  spürbarer Verzögerung. Die Analyse-Frequenz ist deshalb einstellbar; höher als der Stream
  liefert bringt nichts.
* **Bildeinfrieren während des Fokussierens.** Genau dafür gibt es den Zustand
  `AUTOFOCUS_TRIGGERED`: solange er aktiv ist, werden ankommende Frames **komplett
  verworfen** und der gleitende Mittelwert zurückgesetzt. Der AF-Aufruf blockiert außerdem
  den einzigen PTP-Thread, sodass währenddessen physisch kein LiveView-Poll stattfinden
  kann.
* **Interne Videoaufnahme blockiert Fokusbefehle.** Siehe oben – wird als `DeviceBusy`
  erkannt und benannt.
* **Kein Hintergrundbetrieb.** Die Analyse läuft, solange die App im Vordergrund ist. Der
  Bildschirm wird per `FLAG_KEEP_SCREEN_ON` wachgehalten.
* **Ein Score ist kein Schärfegrad in Metern.** Ein Motivwechsel (z. B. kontrastarme weiße
  Wand) senkt den Score genauso wie echte Unschärfe. Die Kombination aus Glättung,
  N-Frames-Bestätigung und Cooldown fängt das ab, aber ein Motiv völlig ohne Kontrast bleibt
  für jedes kontrastbasierte Verfahren nicht auflösbar.

---

## 10. Fehlermeldungen

| Meldung | Ursache und Abhilfe |
|---|---|
| Keine Kamera gefunden | Kabel/OTG-Adapter, Kamera aus, USB-Modus nicht MTP/PTP |
| USB-Berechtigung wurde verweigert | Dialog erneut über „Verbinden" auslösen |
| USB-Schnittstelle konnte nicht belegt werden | Android-MTP-Dienst hält das Gerät – Kabel kurz abziehen |
| PTP-Verbindung fehlgeschlagen | Kamera aus/ein, anderes Kabel, Kamera nicht im Standby |
| LiveView nicht verfügbar | Body meldet 0x9201/0x9203 nicht → siehe Abschnitt 8 |
| Kamera verweigert LiveView (Grund …) | `LiveViewProhibitCondition` im Klartext, z. B. Akku leer |
| Autofokus-Befehl wird nicht unterstützt | 0x90C1 → `OperationNotSupported`, Automatik bleibt aus |
| Fernauslösung wird nicht unterstützt | keiner der Opcodes 0x9207/0x90CB/0x90C0/0x100E akzeptiert; FOTO-Knopf wird deaktiviert |
| StoreNotAvailable beim Auslösen | kein Speicherziel – Karte einlegen, oder in den Einstellungen auf SDRAM umstellen |
| Videoaufnahme wird nicht unterstützt | 0x920A/0x920B fehlen in der Operationsliste |
| Kamera verweigert die Videoaufnahme | `MovRecProhibitCondition` im Klartext, z. B. keine Karte, Karte voll |
| USB-Verbindung getrennt | Kabel gezogen oder Kamera im Standby |
| Kamera antwortet nicht | Timeout auf dem Bulk-Endpunkt – Kamera aus/ein |

---

## 11. Standardwerte

| Parameter | Standard | Bereich |
|---|---|---|
| Schwellwert | 500 | 10 – 5000 |
| Aufeinanderfolgende unscharfe Frames | 5 | 1 – 30 |
| Mittelwertfenster N | 5 | 1 – 30 |
| Cooldown | 3,0 s | 0,5 – 30 s |
| Analyse-Frequenz | 10 fps | 1 – 30 fps |
| Analyse-Auflösung | 320 px Breite | 160 – 640 px |
| Autofokus-Timeout | 5,0 s | 1 – 15 s |
| Fotos auf die Speicherkarte | an | an / aus (SDRAM) |
| PC-Steuerung übernehmen | an | an / aus |
| Fokusüberwachung während Videoaufnahme pausieren | an | an / aus |

---

## Quellenlage für die PTP-Konstanten

Sämtliche Opcodes, Response-Codes und Device-Property-Codes wurden gegen die
Referenzimplementierung von `libgphoto2` verifiziert
([`camlibs/ptp2/ptp.h`](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)),
ebenso die Abläufe „LiveView starten" und „AF-Drive + auf Bereitschaft warten"
([`camlibs/ptp2/library.c`](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c),
[`camlibs/ptp2/config.c`](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/config.c))
und die JPEG-Extraktion aus dem LiveView-Payload.
