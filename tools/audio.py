import json, urllib.request, urllib.parse, sys, re
API="https://commons.wikimedia.org/w/api.php"
UA={"User-Agent":"Versteckspiel/1.0 (https://github.com/h0jeZvgoxFepBQ2C/remote-hide-and-seek)"}
def api(**p):
    p.setdefault("format","json"); p.setdefault("action","query")
    req=urllib.request.Request(API+"?"+urllib.parse.urlencode(p), headers=UA)
    return json.load(urllib.request.urlopen(req,timeout=30))
def strip(h): return re.sub(r"<[^>]+>","",h or "").strip().replace("\n"," ")[:110]

def hunt(query, n=14):
    r=api(list="search", srsearch=query, srnamespace=6, srlimit=n)
    titles=[x["title"] for x in r["query"]["search"]]
    titles=[t for t in titles if not t.startswith("File:LL-Q")]
    if not titles: return []
    out=[]
    for i in range(0,len(titles),15):
        d=api(prop="imageinfo", titles="|".join(titles[i:i+15]), iiprop="url|size|extmetadata")
        for p in d["query"]["pages"].values():
            ii=p.get("imageinfo",[{}])[0]; ext=ii.get("extmetadata",{})
            url=ii.get("url","").split("?")[0]
            if not re.search(r"\.(ogg|oga|wav|flac|mp3|opus)$", url, re.I): continue
            out.append({"t":p["title"][5:], "s":ii.get("size"), "u":url,
                        "l":ext.get("LicenseShortName",{}).get("value"),
                        "a":strip(ext.get("Artist",{}).get("value")),
                        "d":strip(ext.get("ImageDescription",{}).get("value"))})
    return out

for q in sys.argv[1:]:
    print("="*78); print(">>>", q)
    for x in hunt(q):
        if x["s"] and x["s"] > 6_000_000: continue
        print(f'  {x["s"]:>8}B {str(x["l"])[:13]:13} {x["t"][:60]}')
        if x["d"]: print(f'           „{x["d"]}"')
