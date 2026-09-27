#!/usr/bin/env python3
import json
import re
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Safari/537.36"
BASE_HEADERS = {
    "User-Agent": UA,
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
}

def fetch_text(url, timeout=25, max_bytes=5_000_000, headers=None):
    h = dict(BASE_HEADERS)
    h["Accept"] = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read(max_bytes)
        return r.status, r.headers, body.decode("utf-8", "replace")

def extract_rsc(html):
    parts = []
    pat = re.compile(r'self\.__next_f\.push\(\[1,("(?:\\.|[^"\\])*")\]\)</script>', re.S)
    for m in pat.finditer(html):
        try:
            parts.append(json.loads(m.group(1)))
        except Exception:
            pass
    return "\n".join(parts)

def extract_balanced_json(text, marker):
    pos = text.find(marker)
    if pos < 0:
        return None
    pos += len(marker)
    while pos < len(text) and text[pos].isspace():
        pos += 1
    if pos >= len(text) or text[pos] not in "[{":
        return None
    opening = text[pos]
    closing = "]" if opening == "[" else "}"
    depth = 0
    in_string = False
    escaped = False
    for i in range(pos, len(text)):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == opening:
            depth += 1
        elif ch == closing:
            depth -= 1
            if depth == 0:
                raw = text[pos:i+1]
                try:
                    return json.loads(raw)
                except Exception:
                    return None
    return None

def parse_episode_page(url):
    status, headers, html = fetch_text(url)
    rsc = extract_rsc(html)
    play = re.search(r'"playVoucher":"([^"]+)"', rsc)
    title = re.search(r'"shortPlayName":"([^"]+)"', rsc)
    intro = re.search(r'"shotIntroduce":"([^"]*)"', rsc)
    episodes = extract_balanced_json(rsc, '"videoEpisodeInfos":') or []

    play_url = play.group(1) if play else None
    around = ""
    is_lock = None
    if play:
        around = rsc[max(0, play.start()-1800):play.end()+300]
        locks = re.findall(r'"isLock":(true|false)', around)
        if locks:
            is_lock = locks[-1] == "true"

    return {
        "status": status,
        "html_len": len(html),
        "rsc_len": len(rsc),
        "title": title.group(1) if title else None,
        "intro": intro.group(1) if intro else None,
        "episodes": episodes,
        "play_url": play_url,
        "is_lock_near_play": is_lock,
        "html": html,
        "rsc": rsc,
    }

def probe_media(url, referer):
    if not url:
        return {"ok": False, "reason": "no playVoucher"}
    h = dict(BASE_HEADERS)
    h.update({
        "Accept": "*/*",
        "Referer": referer,
        "Range": "bytes=0-2047",
    })
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        body = r.read(2048)
        ct = r.headers.get("content-type", "")
        cr = r.headers.get("content-range", "")
        parsed = urllib.parse.urlparse(url)
        magic = body[:32]
        kind = "unknown"
        if b"#EXTM3U" in body[:256]:
            kind = "hls"
        elif b"ftyp" in body[:64]:
            kind = "mp4"
        elif ct.startswith("video/"):
            kind = "video"
        return {
            "ok": r.status in (200, 206),
            "status": r.status,
            "content_type": ct,
            "content_range": cr,
            "bytes_read": len(body),
            "kind": kind,
            "host": parsed.netloc,
            "path_suffix": parsed.path[-90:],
            "magic_hex": magic.hex()[:64],
        }

sample_base = "https://netshort.com/tr/episode/tahta-giden-ba%C4%9F-2101239560723525634"
print("=== OFFICIAL NETSHORT DIRECT CONTRACT ===")
first = parse_episode_page(sample_base)
print("EP1_PAGE", json.dumps({
    "status": first["status"],
    "html_len": first["html_len"],
    "rsc_len": first["rsc_len"],
    "title": first["title"],
    "episode_count": len(first["episodes"]),
    "is_lock_near_play": first["is_lock_near_play"],
    "has_playVoucher": bool(first["play_url"]),
}, ensure_ascii=False))

free_eps = [e for e in first["episodes"] if isinstance(e, dict) and e.get("isLock") is False]
locked_eps = [e for e in first["episodes"] if isinstance(e, dict) and e.get("isLock") is True]
print("EPISODE_ACCESS", json.dumps({
    "free_count": len(free_eps),
    "locked_count": len(locked_eps),
    "first_free": [e.get("episodeNo") for e in free_eps[:10]],
    "first_locked": [e.get("episodeNo") for e in locked_eps[:10]],
}, ensure_ascii=False))

assert first["status"] == 200, "official episode page is not HTTP 200"
assert len(first["episodes"]) >= 2, "episode list missing"
assert first["play_url"], "free episode playVoucher missing from official HTML"
assert free_eps, "no unlocked episodes found"
media1 = probe_media(first["play_url"], sample_base)
print("EP1_MEDIA", json.dumps(media1, ensure_ascii=False))
assert media1["ok"], "episode 1 media endpoint failed"

# Verify multiple unlocked episodes resolve independently from official pages.
for ep in [e.get("episodeNo") for e in free_eps[:3]]:
    ep_url = sample_base if ep == 1 else f"{sample_base}-ep-{ep}"
    parsed = parse_episode_page(ep_url)
    media = probe_media(parsed["play_url"], ep_url)
    print("EP_MEDIA", json.dumps({
        "episode": ep,
        "page_status": parsed["status"],
        "has_playVoucher": bool(parsed["play_url"]),
        "lock_near_play": parsed["is_lock_near_play"],
        "media": media,
    }, ensure_ascii=False))
    assert parsed["status"] == 200
    assert parsed["play_url"], f"episode {ep} playVoucher missing"
    assert media["ok"], f"episode {ep} media failed"

# Discover candidate search/API strings from official JS only.
script_urls = []
for raw in re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', first["html"], re.I):
    script_urls.append(urllib.parse.urljoin(sample_base, raw))
script_urls = list(dict.fromkeys(script_urls))
candidates = set()
for url in script_urls[:45]:
    try:
        _, _, js = fetch_text(url, timeout=20, max_bytes=3_000_000, headers={"Accept": "*/*"})
    except Exception:
        continue
    for m in re.finditer(r'["\']([^"\']{0,180}(?:search|query_keyword|keyword)[^"\']{0,180})["\']', js, re.I):
        frag = m.group(1)
        if "/web/" in frag or "search" in frag.lower():
            candidates.add(frag[:360])
print("SEARCH_CANDIDATES")
for x in sorted(candidates)[:80]:
    print(x)

# Check likely public search routes without relying on them for playback.
for qs in ["keyword=Tahta", "query=Tahta", "q=Tahta", "search=Tahta"]:
    u = "https://netshort.com/tr/search?" + qs
    try:
        s, _, h = fetch_text(u, timeout=20, max_bytes=2_000_000)
        rsc = extract_rsc(h)
        print("SEARCH_ROUTE", qs, "status", s, "title_hit", ("Tahta Giden Bağ" in rsc or "Tahta Giden Bağ" in h), "rsc_len", len(rsc))
    except Exception as ex:
        print("SEARCH_ROUTE", qs, "ERROR", type(ex).__name__, str(ex))


def provider_enclosing_json_object(text, target_index):
    stack = []
    in_string = False
    escaped = False
    for i, ch in enumerate(text[:target_index + 1]):
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            stack.pop()
    if not stack:
        return None
    start = stack[-1]
    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(text)):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None

def provider_extract_objects(text, marker):
    out = []
    seen = set()
    pos = 0
    while True:
        idx = text.find(marker, pos)
        if idx < 0:
            break
        raw = provider_enclosing_json_object(text, idx)
        if raw and raw not in seen:
            seen.add(raw)
            try:
                out.append(json.loads(raw))
            except Exception:
                pass
        pos = idx + len(marker)
    return out

# Exercise the same structural assumptions used by NetShort.kt.
detail_objs = provider_extract_objects(first["rsc"], '"videoEpisodeInfos"')
detail_obj = next(
    (o for o in detail_objs
     if isinstance(o, dict)
     and isinstance(o.get("videoEpisodeInfos"), list)
     and o.get("shortPlayName")),
    None,
)
assert detail_obj is not None, "provider-style detail object extraction failed"
assert len(detail_obj["videoEpisodeInfos"]) == 45, "provider-style episode list count mismatch"
provider_free = [e for e in detail_obj["videoEpisodeInfos"] if isinstance(e, dict) and e.get("isLock") is False]
assert [e.get("episodeNo") for e in provider_free[:7]] == [1, 2, 3, 4, 5, 6, 7], "provider-style free episode parsing mismatch"

play_objs = provider_extract_objects(first["rsc"], '"playVoucher"')
play_obj = next(
    (o for o in play_objs
     if isinstance(o, dict)
     and str(o.get("playVoucher", "")).startswith("http")
     and not o.get("isLock", False)),
    None,
)
assert play_obj is not None, "provider-style playVoucher extraction failed"
assert "cfcdn.netshort.com" in play_obj["playVoucher"], "unexpected official media host"

_, _, all_html = fetch_text("https://netshort.com/tr/all-episodes", timeout=25, max_bytes=5_000_000)
all_rsc = extract_rsc(all_html)
catalog_objs = provider_extract_objects(all_rsc, '"shortPlayNameUrl"')
catalog = []
for obj in catalog_objs:
    if not isinstance(obj, dict):
        continue
    u = str(obj.get("shortPlayNameUrl", ""))
    n = str(obj.get("shortPlayNameNoHL") or obj.get("shortPlayName") or "")
    if "/episode/" in u and n:
        catalog.append((n, u))
catalog_unique = list(dict.fromkeys(catalog))
print("PROVIDER_STYLE", json.dumps({
    "detail_title": detail_obj.get("shortPlayName"),
    "episode_count": len(detail_obj["videoEpisodeInfos"]),
    "unlocked_count": len(provider_free),
    "play_host": urllib.parse.urlparse(play_obj["playVoucher"]).netloc,
    "catalog_count": len(catalog_unique),
    "catalog_samples": catalog_unique[:5],
}, ensure_ascii=False))
assert len(catalog_unique) >= 10, "provider-style catalogue parsing returned too few items"

print("NETSHORT_DIRECT_CONTRACT_PASS")
