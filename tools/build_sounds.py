import subprocess, wave, struct, math, os, sys, json, urllib.request, urllib.parse, re
sys.path.insert(0, ".")
from audio import api, strip

SR = 22050
UA = {"User-Agent": "Versteckspiel/1.0 (https://github.com/h0jeZvgoxFepBQ2C/remote-hide-and-seek)"}

# (Ziel-Id, Commons-Datei, Laenge in s, welcher laute Abschnitt: 0 = lautester)
SPECS = [
    # --- Tiere ---
    ("cat",     "File:Meow of a Siamese cat - freemaster2.wav",      1.3, 0),
    ("dog",     "File:A dog making noises and barking.flac",         1.0, 0),
    ("cow",     "File:Mudchute cow 1.ogg",                           2.0, 0),
    ("frog",    "File:Frogs croaking in a pipe in Thailand.flac",    1.6, 0),
    ("duck",    "File:Domestic duck sound 01.wav",                   1.6, 0),
    ("rooster", "File:Kukuriku.flac",                                2.2, 0),
    ("bear",    "File:Yellowstone sound library - Grizzly Bears Roar - 001.mp3", 2.2, 0),
    ("owl",     "File:Strix aluco - Tawny Owl XC494801.mp3",         2.5, 0),
    # --- Lustig ---
    ("fart1",   "File:Flatulence.wav",                               1.4, 0),
    ("fart2",   "File:Flatus.wav",                                   1.4, 0),
    ("fart3",   "File:Human fart.wav",                               1.2, 0),
    ("fart4",   "File:Menselijke wind drietraps.wav",                2.6, 0),
    ("burp1",   "File:Burp.wav",                                     1.4, 0),
    ("burp2",   "File:Burp.wav",                                     1.4, 1),
    ("laugh1",  "File:Short clip of girl laughing.wav",              1.8, 0),
    ("laugh2",  "File:72843 lonemonk approx-800-laughter-only-1.wav",2.5, 0),
    # --- Menschen ---
    ("yodel1",  "File:Alpine specialty by George P. Watson.mp3",     2.6, 0),
    ("yodel2",  "File:Alpine specialty by George P. Watson.mp3",     2.6, 1),
    ("yodel3",  "File:Alpine specialty by George P. Watson.mp3",     2.6, 2),
    ("yodel4",  "File:Cusb-cyl5015d.mp3",                            2.6, 0),
    ("whistle", "File:Alice J. Shaw and her daughters whistling.mp3",2.4, 0),
    ("clap",    "File:72844 lonemonk approx-800-laughter-and-clapter-1.wav", 2.4, 0),
    ("yippie",  "File:277021 sandermotions applause-2.wav",          2.0, 0),
]

os.makedirs("orig", exist_ok=True)
os.makedirs("ready", exist_ok=True)

def fetch(title):
    safe = re.sub(r"[^A-Za-z0-9.]+", "_", title[5:])[:80]
    for existing in os.listdir("orig"):
        if existing.startswith(safe + "."): return "orig/" + existing, None
    d = api(prop="imageinfo", titles=title, iiprop="url|extmetadata")
    page = list(d["query"]["pages"].values())[0]
    if "imageinfo" not in page: return None, None
    ii = page["imageinfo"][0]; ext = ii.get("extmetadata", {})
    url = ii["url"].split("?")[0]
    out = f'orig/{safe}.{url.rsplit(".",1)[1].lower()}'
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
        open(out, "wb").write(r.read())
    return out, {
        "title": title[5:],
        "page": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
        "license": ext.get("LicenseShortName", {}).get("value"),
        "author": strip(ext.get("Artist", {}).get("value")),
    }

def decode(src):
    out = subprocess.run(["ffmpeg","-v","error","-i",src,"-ac","1","-ar",str(SR),"-f","s16le","-"],
                         capture_output=True, check=True).stdout
    return list(struct.unpack("<%dh" % (len(out)//2), out[:len(out)//2*2]))

def windows(s, win, count):
    """Die `count` energiereichsten Fenster, die sich nicht ueberlappen."""
    n = len(s); w = min(win, n)
    pre = [0.0]*(n+1)
    for i, v in enumerate(s): pre[i+1] = pre[i] + float(v)*v
    step = SR // 50
    cands = [(pre[i+w]-pre[i], i) for i in range(0, n-w+1, step)]
    cands.sort(reverse=True)
    picked = []
    for e, i in cands:
        if all(abs(i-j) >= w for j in picked):
            picked.append(i)
            if len(picked) == count: break
    return picked or [0]

def process(src, dst, secs, pick):
    s = decode(src)
    w = int(secs*SR)
    starts = windows(s, w, pick+1)
    i = starts[min(pick, len(starts)-1)]
    lead = int(0.12*SR)
    i = max(0, i-lead)
    seg = s[i:i+w+lead]
    f = int(0.015*SR)
    for k in range(min(f, len(seg))):
        seg[k] = int(seg[k]*k/f); seg[-1-k] = int(seg[-1-k]*k/f)
    peak = max(1, max(abs(x) for x in seg))
    g = 0.95*32767/peak
    data = b"".join(struct.pack("<h", max(-32768, min(32767, int(x*g)))) for x in seg)
    wv = wave.open(dst, "wb"); wv.setnchannels(1); wv.setsampwidth(2); wv.setframerate(SR)
    wv.writeframes(data); wv.close()
    return len(s)/SR, len(seg)/SR

credits, cache = {}, {}
for sid, title, secs, pick in SPECS:
    src, meta = fetch(title)
    if not src:
        print(f"!! {sid}: {title} nicht gefunden"); continue
    if meta: cache[title] = meta
    credits[sid] = dict(cache.get(title, {"title": title[5:]}), part=pick)
    orig, cut = process(src, f"ready/{sid}.wav", secs, pick)
    print(f'{sid:9} {orig:7.1f}s -> {cut:4.1f}s  Abschnitt {pick}  ({title[5:45]})')

json.dump(credits, open("credits.json", "w"), indent=2, ensure_ascii=False)
