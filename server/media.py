"""Library browsing and management for the native app and the assistant.

Every operation is a plain async function (so the assistant can call it as a tool) with a thin HTTP
route on top. Searches stay targeted: never a whole-library search, which starves the arr task slots.
"""
import asyncio

from fastapi import APIRouter, Depends, HTTPException

from server.core import (BASE, LABEL, MOVIE_APPS, arr, configured, current_user, poster, quality_name, safe,
                         seerr, tautulli)

router = APIRouter(prefix=BASE + "/api", dependencies=[Depends(current_user)])


def _need(app):
    if app not in ("sonarr", *MOVIE_APPS, "prowlarr") or not configured(app):
        raise HTTPException(404, f"{app} is not configured")


# ---------------------------------------------------------------- shapes
def _show(s):
    st = s.get("statistics") or {}
    return {"id": s["id"], "title": s.get("title"), "year": s.get("year"), "status": s.get("status"),
            "network": s.get("network"), "monitored": s.get("monitored"), "seriesType": s.get("seriesType"),
            "episodes": st.get("episodeFileCount", 0), "episodesTotal": st.get("episodeCount", 0),
            "sizeOnDisk": st.get("sizeOnDisk", 0), "poster": poster(s.get("images")),
            "profileId": s.get("qualityProfileId"), "tvdbId": s.get("tvdbId"), "path": s.get("path")}


def _movie(app, m):
    f = m.get("movieFile") or {}
    return {"app": app, "label": LABEL[app], "id": m["id"], "title": m.get("title"), "year": m.get("year"),
            "monitored": m.get("monitored"), "hasFile": m.get("hasFile"), "status": m.get("status"),
            "quality": quality_name(f.get("quality")), "sizeOnDisk": m.get("sizeOnDisk", 0),
            "poster": poster(m.get("images")), "profileId": m.get("qualityProfileId"), "tmdbId": m.get("tmdbId"),
            "overview": m.get("overview"), "runtime": m.get("runtime"), "path": m.get("path")}


# ---------------------------------------------------------------- shows (Sonarr)
async def list_shows(query: str = ""):
    shows = [_show(s) for s in await arr("sonarr", "/series")]
    q = query.lower().strip()
    if q:
        shows = [s for s in shows if q in (s["title"] or "").lower()]
    return sorted(shows, key=lambda s: (s["title"] or "").lower())


async def get_show(series_id: int):
    s, eps, files = await asyncio.gather(arr("sonarr", f"/series/{series_id}"),
                                         arr("sonarr", "/episode", params={"seriesId": series_id}),
                                         arr("sonarr", "/episodefile", params={"seriesId": series_id}))
    fmap = {f["id"]: f for f in files}
    seasons = {}
    for e in eps:
        f = fmap.get(e.get("episodeFileId"))
        seasons.setdefault(e["seasonNumber"], []).append({
            "id": e["id"], "season": e["seasonNumber"], "episode": e["episodeNumber"], "title": e.get("title"),
            "airDate": e.get("airDate"), "monitored": e.get("monitored"), "hasFile": e.get("hasFile"),
            "quality": quality_name(f.get("quality")) if f else None,
            "languages": [l.get("name") for l in (f or {}).get("languages", [])],
            "releaseGroup": (f or {}).get("releaseGroup")})
    out = _show(s)
    out["overview"] = s.get("overview")
    out["seasons"] = [{"season": n, "monitored": next((x.get("monitored") for x in s.get("seasons", [])
                                                        if x["seasonNumber"] == n), None),
                       "episodes": sorted(v, key=lambda e: e["episode"])} for n, v in sorted(seasons.items())]
    return out


# ---------------------------------------------------------------- movies (Radarr / Radarr 4K)
async def list_movies(app: str = "radarr", query: str = ""):
    movies = [_movie(app, m) for m in await arr(app, "/movie")]
    q = query.lower().strip()
    if q:
        movies = [m for m in movies if q in (m["title"] or "").lower()]
    return sorted(movies, key=lambda m: ((m["title"] or "").lower(), m["year"] or 0))


async def get_movie(app: str, movie_id: int):
    return _movie(app, await arr(app, f"/movie/{movie_id}"))


# ---------------------------------------------------------------- lookup + add
async def lookup(kind: str, term: str):
    """Find something NOT necessarily in the library yet. kind = show | movie."""
    if kind == "show":
        res = await arr("sonarr", "/series/lookup", params={"term": term})
        return [{"kind": "show", "title": r.get("title"), "year": r.get("year"), "tvdbId": r.get("tvdbId"),
                 "network": r.get("network"), "status": r.get("status"), "overview": (r.get("overview") or "")[:300],
                 "inLibrary": bool(r.get("id")), "libraryId": r.get("id"), "poster": poster(r.get("images"))}
                for r in res[:15]]
    res = await arr("radarr", "/movie/lookup", params={"term": term})
    have4k = {m["tmdbId"] for m in await safe(arr("radarr4k", "/movie"), [])} if configured("radarr4k") else set()
    return [{"kind": "movie", "title": r.get("title"), "year": r.get("year"), "tmdbId": r.get("tmdbId"),
             "status": r.get("status"), "overview": (r.get("overview") or "")[:300],
             "inLibrary": bool(r.get("id")), "libraryId": r.get("id"), "in4k": r.get("tmdbId") in have4k,
             "poster": poster(r.get("images"))} for r in res[:15]]


async def options(app: str):
    """Quality profiles + root folders, needed to add something."""
    profiles, roots = await asyncio.gather(arr(app, "/qualityprofile"), arr(app, "/rootfolder"))
    return {"profiles": [{"id": p["id"], "name": p["name"]} for p in profiles],
            "rootFolders": [{"id": r["id"], "path": r["path"], "free": r.get("freeSpace")} for r in roots]}


async def _defaults(app):
    """Defaults = whatever most of the library already uses (profile + root folder)."""
    items = await arr(app, "/series" if app == "sonarr" else "/movie")
    from collections import Counter
    prof = Counter(i.get("qualityProfileId") for i in items).most_common(1)
    root = Counter((i.get("rootFolderPath") or i.get("path", "").rsplit("/", 1)[0]).rstrip("/") for i in items).most_common(1)
    return (prof[0][0] if prof else None), (root[0][0] if root else None)


async def add_show(tvdb_id: int, profile_id: int | None = None, root_folder: str | None = None,
                   monitor: str = "all", search: bool = True, series_type: str = "standard"):
    res = await arr("sonarr", "/series/lookup", params={"term": f"tvdb:{tvdb_id}"})
    if not res:
        raise HTTPException(404, "show not found")
    s = res[0]
    if s.get("id"):
        return {"ok": True, "already": True, "id": s["id"], "title": s.get("title")}
    dp, dr = await _defaults("sonarr")
    s.update(qualityProfileId=profile_id or dp, rootFolderPath=root_folder or dr, monitored=True,
             seriesType=series_type, seasonFolder=True,
             addOptions={"monitor": monitor, "searchForMissingEpisodes": search, "searchForCutoffUnmetEpisodes": False})
    added = await arr("sonarr", "/series", "POST", body=s)
    return {"ok": True, "id": added["id"], "title": added.get("title")}


async def add_movie(app: str, tmdb_id: int, profile_id: int | None = None, root_folder: str | None = None,
                    search: bool = True):
    m = await arr(app, "/movie/lookup/tmdb", params={"tmdbId": tmdb_id})
    if m.get("id"):
        return {"ok": True, "already": True, "id": m["id"], "title": m.get("title")}
    dp, dr = await _defaults(app)
    m.update(qualityProfileId=profile_id or dp, rootFolderPath=root_folder or dr, monitored=True,
             minimumAvailability="released", addOptions={"searchForMovie": search, "monitor": "movieOnly"})
    added = await arr(app, "/movie", "POST", body=m)
    return {"ok": True, "id": added["id"], "title": added.get("title")}


# ---------------------------------------------------------------- monitor + search + releases
async def set_monitored(app: str, ids: list[int], monitored: bool, level: str = "item"):
    """level: 'item' (a series or movie) or 'episode' (Sonarr episode ids)."""
    if app == "sonarr" and level == "episode":
        await arr("sonarr", "/episode/monitor", "PUT", body={"episodeIds": ids, "monitored": monitored})
    elif app == "sonarr":
        for i in ids:
            s = await arr("sonarr", f"/series/{i}")
            s["monitored"] = monitored
            await arr("sonarr", f"/series/{i}", "PUT", body=s)
    else:
        await arr(app, "/movie/editor", "PUT", body={"movieIds": ids, "monitored": monitored})
    return {"ok": True, "count": len(ids)}


async def run_search(app: str, ids: list[int]):
    if app == "sonarr":
        cmd = {"name": "EpisodeSearch", "episodeIds": ids[:50]}
    elif app in MOVIE_APPS:
        cmd = {"name": "MoviesSearch", "movieIds": ids[:50]}
    else:
        raise HTTPException(400)
    r = await arr(app, "/command", "POST", body=cmd)
    return {"ok": True, "command": r.get("id"), "searched": len(ids[:50])}


async def releases(app: str, episode_id: int | None = None, movie_id: int | None = None):
    """Interactive search: what the indexers offer for one episode/movie, best first."""
    params = {"episodeId": episode_id} if app == "sonarr" else {"movieId": movie_id}
    res = await arr(app, "/release", params=params)
    out = [{"guid": r["guid"], "indexerId": r.get("indexerId"), "title": r.get("title"), "indexer": r.get("indexer"),
            "quality": quality_name(r.get("quality")), "size": r.get("size"), "seeders": r.get("seeders"),
            "protocol": r.get("protocol"), "age": r.get("ageHours"), "approved": r.get("approved"),
            "rejections": (r.get("rejections") or [])[:3], "languages": [l.get("name") for l in r.get("languages") or []]}
           for r in res]
    out.sort(key=lambda r: (not r["approved"], -(r["seeders"] or 0)))
    return out[:40]


async def grab(app: str, guid: str, indexer_id: int):
    await arr(app, "/release", "POST", body={"guid": guid, "indexerId": indexer_id})
    return {"ok": True}


async def history(app: str, page_size: int = 30):
    params = {"pageSize": page_size, "sortKey": "date", "sortDirection": "descending"}
    params.update({"includeSeries": "true", "includeEpisode": "true"} if app == "sonarr" else {"includeMovie": "true"})
    h = await arr(app, "/history", params=params)
    EV = {1: "grabbed", 3: "imported", 4: "failed", 5: "deleted", 6: "renamed", 7: "ignored", 8: "imported"}
    out = []
    for x in h.get("records", []):
        if app == "sonarr":
            s, e = x.get("series") or {}, x.get("episode") or {}
            title, sub = s.get("title"), f"S{e.get('seasonNumber', 0):02}E{e.get('episodeNumber', 0):02}"
        else:
            m = x.get("movie") or {}
            title, sub = m.get("title"), str(m.get("year") or "")
        out.append({"when": x.get("date"), "event": x.get("eventType") if isinstance(x.get("eventType"), str)
                    else EV.get(x.get("eventType"), x.get("eventType")), "title": title, "sub": sub,
                    "release": x.get("sourceTitle"), "quality": quality_name(x.get("quality"))})
    return out


# ---------------------------------------------------------------- indexers (Prowlarr)
async def indexers():
    idx, status = await asyncio.gather(arr("prowlarr", "/indexer"), safe(arr("prowlarr", "/indexerstatus"), []))
    bad = {s["indexerId"]: s for s in status if isinstance(s, dict)}
    return [{"id": i["id"], "name": i.get("name"), "enabled": i.get("enable"), "protocol": i.get("protocol"),
             "priority": i.get("priority"), "privacy": i.get("privacy"),
             "failing": i["id"] in bad, "disabledTill": (bad.get(i["id"]) or {}).get("disabledTill")}
            for i in sorted(idx, key=lambda i: (not i.get("enable"), i.get("name", "").lower()))]


async def indexer_search(query: str, kind: str = "search"):
    res = await arr("prowlarr", "/search", params={"query": query, "type": kind, "limit": 100})
    return [{"title": r.get("title"), "indexer": r.get("indexer"), "size": r.get("size"),
             "seeders": r.get("seeders"), "protocol": r.get("protocol"), "age": r.get("age"),
             "categories": [c.get("name") for c in r.get("categories") or []][:2]}
            for r in sorted(res, key=lambda r: -(r.get("seeders") or 0))[:50]]


async def test_indexer(indexer_id: int):
    i = await arr("prowlarr", f"/indexer/{indexer_id}")
    try:
        await arr("prowlarr", "/indexer/test", "POST", body=i)
        return {"ok": True}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": str(e)[:200]}


# ---------------------------------------------------------------- stats (Tautulli)
async def watch_stats(days: int = 30):
    s = await tautulli("get_home_stats", time_range=days, stats_count=5)
    out = {}
    for block in s:
        out[block.get("stat_id")] = [{"title": r.get("title") or r.get("friendly_name") or r.get("user"),
                                      "plays": r.get("total_plays"), "duration": r.get("total_duration")}
                                     for r in block.get("rows", [])]
    return out


# ---------------------------------------------------------------- seerr extras
async def request_media(kind: str, tmdb_id: int, is4k: bool = False, seasons: list[int] | None = None):
    body = {"mediaType": "tv" if kind == "show" else "movie", "mediaId": tmdb_id, "is4k": is4k}
    if kind == "show":
        body["seasons"] = seasons or "all"
    r = await seerr("/request", "POST", body=body)
    return {"ok": True, "id": r.get("id")}


# ---------------------------------------------------------------- routes
@router.get("/shows")
async def r_shows(q: str = ""):
    _need("sonarr")
    return {"items": await list_shows(q)}


@router.get("/shows/{series_id}")
async def r_show(series_id: int):
    _need("sonarr")
    return await get_show(series_id)


@router.get("/movies/{app}")
async def r_movies(app: str, q: str = ""):
    _need(app)
    return {"items": await list_movies(app, q)}


@router.get("/movies/{app}/{movie_id}")
async def r_movie(app: str, movie_id: int):
    _need(app)
    return await get_movie(app, movie_id)


@router.get("/lookup")
async def r_lookup(kind: str, term: str):
    _need("sonarr" if kind == "show" else "radarr")
    return {"items": await lookup(kind, term)}


@router.get("/options/{app}")
async def r_options(app: str):
    _need(app)
    return await options(app)


@router.post("/shows")
async def r_add_show(body: dict):
    _need("sonarr")
    return await add_show(int(body["tvdbId"]), body.get("profileId"), body.get("rootFolder"),
                          body.get("monitor", "all"), body.get("search", True), body.get("seriesType", "standard"))


@router.post("/movies/{app}")
async def r_add_movie(app: str, body: dict):
    _need(app)
    return await add_movie(app, int(body["tmdbId"]), body.get("profileId"), body.get("rootFolder"),
                           body.get("search", True))


@router.post("/monitor/{app}")
async def r_monitor(app: str, body: dict):
    _need(app)
    return await set_monitored(app, [int(i) for i in body["ids"]], bool(body["monitored"]), body.get("level", "item"))


@router.post("/searchnow/{app}")
async def r_search(app: str, body: dict):
    _need(app)
    return await run_search(app, [int(i) for i in body["ids"]])


@router.get("/releases/{app}")
async def r_releases(app: str, episodeId: int | None = None, movieId: int | None = None):
    _need(app)
    return {"items": await releases(app, episodeId, movieId)}


@router.post("/releases/{app}/grab")
async def r_grab(app: str, body: dict):
    _need(app)
    return await grab(app, body["guid"], int(body["indexerId"]))


@router.get("/history/{app}")
async def r_history(app: str):
    _need(app)
    return {"items": await history(app)}


@router.get("/indexers")
async def r_indexers():
    _need("prowlarr")
    return {"items": await indexers()}


@router.get("/indexers/search")
async def r_indexer_search(q: str):
    _need("prowlarr")
    return {"items": await indexer_search(q)}


@router.post("/indexers/{indexer_id}/test")
async def r_test_indexer(indexer_id: int):
    _need("prowlarr")
    return await test_indexer(indexer_id)


@router.get("/stats")
async def r_stats(days: int = 30):
    if not configured("tautulli"):
        return {}
    return await watch_stats(days)
