#!/usr/bin/env python3
import json, re, sys, urllib.parse, urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36"
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
}

def fetch(url, timeout=25, max_bytes=4_000_000):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read(max_bytes)
        return r.status, r.headers.get("content-type",""), body.decode("utf-8","replace")

def print_matches(label, text, patterns, limit=80):
    print(f"\n## {label}")
    seen = set()
    count = 0
    for pat in patterns:
        for m in re.finditer(pat, text, re.I):
            a=max(0,m.start()-160); b=min(len(text),m.end()+240)
            frag=re.sub(r"\s+"," ",text[a:b])
            frag=re.sub(r'([?&](?:token|signature|auth|key|secret)=[^&"\s]+)', r'\1<redacted>', frag, flags=re.I)
            if frag in seen: continue
            seen.add(frag)
            print(f"[{pat}] {frag[:700]}")
            count += 1
            if count >= limit:
                print("...match limit reached")
                return

home = "https://netshort.com/tr"
status, ctype, html = fetch(home)
print(f"HOME status={status} type={ctype} bytes={len(html)}")

episode_urls = []
for raw in re.findall(r'href=["\']([^"\']+/tr/episode/[^"\']+)["\']', html, re.I):
    episode_urls.append(urllib.parse.urljoin(home, raw))
episode_urls = list(dict.fromkeys(episode_urls))
print(f"HOME episode_links={len(episode_urls)}")
for u in episode_urls[:5]:
    print("EPISODE_LINK", u)

sample = episode_urls[0] if episode_urls else "https://netshort.com/tr/episode/tahta-giden-ba%C4%9F-2101239560723525634"
estatus, ectype, ehtml = fetch(sample)
print(f"EPISODE status={estatus} type={ectype} bytes={len(ehtml)} url={sample}")

patterns = [
    r"playVoucher", r"shortPlayId", r"episodeId", r"sdkVid",
    r"allepisode", r"theaters", r"foryou", r"\.m3u8", r"\.mp4",
    r"/api/", r"__NEXT_DATA__", r"self\.__next_f", r"shortPlayEpisodeInfos"
]
print_matches("episode-html", ehtml, patterns)

script_urls = []
for raw in re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', ehtml, re.I):
    script_urls.append(urllib.parse.urljoin(sample, raw))
script_urls = list(dict.fromkeys(script_urls))
print(f"SCRIPT count={len(script_urls)}")

for i, url in enumerate(script_urls[:40],1):
    try:
        s, ct, js = fetch(url, timeout=20, max_bytes=2_500_000)
        interesting = any(re.search(p, js, re.I) for p in patterns)
        print(f"SCRIPT[{i}] status={s} interesting={interesting} bytes={len(js)} url={url}")
        if interesting:
            print_matches(f"script-{i}", js, patterns, limit=30)
    except Exception as ex:
        print(f"SCRIPT[{i}] ERROR {type(ex).__name__}: {ex} url={url}")

# Reference-only public API probe. This is not accepted as the provider dependency
# unless direct NetShort web/API discovery fails and the dependency is explicitly reviewed.
ref_base = "https://netshort.sansekai.my.id/api/netshort"
for path in ("/theaters",):
    try:
        s, ct, body = fetch(ref_base+path, timeout=20, max_bytes=1_000_000)
        print(f"REFERENCE {path} status={s} type={ct} bytes={len(body)}")
        data=json.loads(body)
        print("REFERENCE_JSON_TYPE", type(data).__name__)
        if isinstance(data,list) and data:
            first=data[0]
            print("REFERENCE_FIRST_KEYS", sorted(first.keys())[:40] if isinstance(first,dict) else type(first).__name__)
            infos=first.get("contentInfos") if isinstance(first,dict) else None
            if isinstance(infos,list) and infos:
                item=infos[0]
                print("REFERENCE_ITEM_KEYS", sorted(item.keys())[:50])
                sid=item.get("shortPlayId")
                print("REFERENCE_SHORTPLAY_ID", sid)
                if sid:
                    s2, ct2, b2 = fetch(ref_base+"/allepisode?shortPlayId="+urllib.parse.quote(str(sid)), timeout=20, max_bytes=1_500_000)
                    print(f"REFERENCE allepisode status={s2} type={ct2} bytes={len(b2)}")
                    d2=json.loads(b2)
                    print("REFERENCE_DETAIL_KEYS", sorted(d2.keys())[:80] if isinstance(d2,dict) else type(d2).__name__)
                    eps=d2.get("shortPlayEpisodeInfos") if isinstance(d2,dict) else None
                    if isinstance(eps,list):
                        print("REFERENCE_EPISODES", len(eps))
                        if eps:
                            e=eps[0]
                            safe={k:e.get(k) for k in ["episodeId","episodeNo","isLock","isVip","isAd","playClarity","sdkVid"]}
                            pv=e.get("playVoucher")
                            safe["playVoucherSchemeHost"]=None
                            if isinstance(pv,str) and pv:
                                pu=urllib.parse.urlparse(pv)
                                safe["playVoucherSchemeHost"]=f"{pu.scheme}://{pu.netloc}"
                                safe["playVoucherPathSuffix"]=pu.path[-80:]
                            print("REFERENCE_FIRST_EP", json.dumps(safe,ensure_ascii=False))
    except Exception as ex:
        print(f"REFERENCE ERROR {type(ex).__name__}: {ex}")

print("\nPROBE_DONE")
