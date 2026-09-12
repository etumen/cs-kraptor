#!/usr/bin/env python3
"""Patch only audited method entry points in a SHA-pinned v63 disassembly.

All original provider metadata, detail/category code, application-package gate,
TMDB helper and other extractors remain present. No invented v72 source.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
provider_path = root / "com/kraptor/FullHDFilmizlesene.smali"
rapid_path = root / "com/kraptor/RapidVid.smali"


def replace_method(text, signature, body):
    pattern = r"(?m)^\.method " + re.escape(signature) + r"\n.*?^\.end method"
    result, count = re.subn(pattern, ".method " + signature + "\n" + body + "\n.end method", text, flags=re.S | re.M)
    assert count == 1, (signature, count)
    return result


text = provider_path.read_text()
text = replace_method(text,
    "public search(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", """    .registers 4
    sget-object v0, Lcom/kraptor/FullHDFilmizleseneHelper;->INSTANCE:Lcom/kraptor/FullHDFilmizleseneHelper;
    invoke-virtual {v0}, Lcom/kraptor/FullHDFilmizleseneHelper;->isAllowedVersion()Z
    move-result v0
    if-nez v0, :allowed
    invoke-static {}, Lkotlin/collections/CollectionsKt;->emptyList()Ljava/util/List;
    move-result-object v0
    return-object v0
    :allowed
    invoke-static {p0, p1, p2}, Lcom/etumen/fhd/RecoveryProvider;->search(Lcom/lagradost/cloudstream3/MainAPI;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
    move-result-object v0
    return-object v0""")
text = replace_method(text,
    "public loadLinks(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", """    .registers 7
    invoke-static/range {p0 .. p5}, Lcom/etumen/fhd/RecoveryProvider;->loadLinks(Lcom/lagradost/cloudstream3/MainAPI;Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
    move-result-object v0
    return-object v0""")

# Only two ordinary GET calls remain: the retained homepage and detail methods.
pattern = r"(?m)^(    invoke-static/range \{v(\d+) \.\. v\d+\}, Lcom/lagradost/nicehttp/Requests;->get\$default\([^\n]+)$"


def network_guard(match):
    start = int(match[2])
    # JVM/dex register offsets: receiver=0, url=1, cacheTime long=9..10,
    # interceptor=11, continuation=14, Kotlin default bit mask=15.
    return ("    invoke-static {}, Lcom/etumen/fhd/RecoveryHttp;->interceptor()Lokhttp3/Interceptor;\n"
            f"    move-result-object v{start + 11}\n"
            f"    const/16 v{start + 15}, 0xdfe\n" + match[1])


text, guards = re.subn(pattern, network_guard, text)
assert guards == 2, guards
provider_path.write_text(text)

text = rapid_path.read_text()
text = replace_method(text,
    "static synthetic getUrl$suspendImpl(Lcom/kraptor/RapidVid;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", """    .registers 7
    invoke-static/range {p0 .. p5}, Lcom/etumen/fhd/RecoveryProvider;->rapid(Lcom/lagradost/cloudstream3/utils/ExtractorApi;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
    move-result-object v0
    return-object v0""")
rapid_path.write_text(text)
print("Patched: search, loadLinks, RapidVid; guarded retained homepage/detail GETs: 2")
