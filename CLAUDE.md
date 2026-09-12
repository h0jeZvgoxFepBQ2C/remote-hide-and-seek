# Hinweise für Claude

## Keine persönlichen Daten in diesem Repo

Dieses Repo ist öffentlich und soll **anonym** bleiben. Niemals einchecken,
auch nicht in Kommentaren, Commit-Nachrichten, Beispielen oder Screenshots:

- **Klarnamen** – kein Vor- oder Nachname, weder in `LICENSE`, `README.md`,
  Code-Kommentaren noch als Copyright-Halter
- **E-Mail-Adressen** – auch nicht als Kontakt in `User-Agent`-Kennungen oder
  Beispielaufrufen. Ausnahme ist allein die anonyme GitHub-Adresse
  `h0jeZvgoxFepBQ2C@users.noreply.github.com`, die Git technisch braucht
- **IP-Adressen aus dem Heimnetz**, WLAN-Namen, Geräte- oder MAC-Adressen –
  in Skripten immer neutrale Platzhalter wie `192.168.1.255` verwenden
- **Screenshots mit Statusleiste** – die zeigt Uhrzeit, Akkustand und laufende
  Apps. Vor dem Ablegen oben abschneiden:
  `ffmpeg -i roh.png -vf "crop=1080:2140:0:90" docs/bild.png`
- **Pfade mit Benutzernamen** – `local.properties` bleibt in `.gitignore`

## Commit-Identität

Sie ist lokal auf den anonymen GitHub-Handle gesetzt. Vor einem Commit prüfen,
dass sie nicht auf eine persönliche Adresse zurückgefallen ist:

```sh
git config user.name    # h0jeZvgoxFepBQ2C
git config user.email   # h0jeZvgoxFepBQ2C@users.noreply.github.com
```

Die Identität steckt in **jedem** Commit und ist über die GitHub-API abrufbar.
Ein `git push` allein räumt sie nicht weg – dafür muss die Historie neu
geschrieben und `--force` gepusht werden, und Tags bzw. Releases müssen neu
gesetzt werden, weil sie sonst alte Commits am Leben halten.

## Vor jedem Push prüfen

```sh
grep -rIn -E "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}" \
  --exclude-dir=.git --exclude-dir=build --exclude="*.apk" .
git log --all --format='%an <%ae> | %cn <%ce>' | sort -u
```

Beide Ausgaben dürfen nur den anonymen Handle enthalten.
