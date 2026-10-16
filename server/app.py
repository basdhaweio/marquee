"""Marquee — one admin hub for the media stack on jerry-EQ.

The browser never sees an API key: every call to Sonarr/Radarr/Seerr/Tautulli happens here,
server-side, and the PWA only gets curated JSON. Access is Plex sign-in, limited to the
usernames in ALLOWED_PLEX_USERS. Everything is mounted under BASE_PATH (default /hub) so it can
sit behind Tailscale Funnel at https://<host>/hub without path rewriting.
"""
import asyncio
import os
import shutil
import time
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import APIRouter, Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

from server.core import (ALLOWED, APP_SESSION_DAYS, BASE, CLIENT_ID, COOKIE_SECURE, LABEL, LINKS, SERVICES,
                         SESSION_DAYS, WEB, arr, configured, current_user, http, poster, safe, seerr, sign,
                         tautulli)

app = FastAPI(title="Marquee", docs_url=None, redoc_url=None, openapi_url=None)


# ---------------------------------------------------------------- plex sign-in
PLEX_HEADERS = {"X-Plex-Product": "Marquee", "X-Plex-Client-Identifier": CLIENT_ID, "Accept": "application/json"}
auth = APIRouter(prefix=BASE + "/auth")


@auth.post("/pin")
async def new_pin(request: Request, client: str = "web", link: bool = False):
    """client=android: no forward back to the PWA; the app polls instead.
    link=true makes a short code to type in at plex.tv/link on any device (strong PINs don't work there)."""
    r = await http.post("https://plex.tv/api/v2/pins", params={"strong": "false" if link else "true"}, headers=PLEX_HEADERS)
    r.raise_for_status()
    pin = r.json()
    params = {"clientID": CLIENT_ID, "code": pin["code"], "context[device][product]": "Marquee"}
    # Plex's page bounces to plex.tv (which the Plex app then grabs) when forwardUrl isn't http(s), so the Android
    # app gets none: it shows the page in its own WebView and its poll notices the sign-in.
    if client != "android":
        params["forwardUrl"] = str(request.base_url).rstrip("/") + BASE + "/#/signed-in"
    url = "https://app.plex.tv/auth#?" + str(httpx.QueryParams(params))
    return {"id": pin["id"], "authUrl": url, "code": pin["code"] if link else None}


@auth.get("/pin/{pin_id}")
async def check_pin(pin_id: int, response: Response, client: str = "web"):
    r = await http.get(f"https://plex.tv/api/v2/pins/{pin_id}", headers=PLEX_HEADERS)
    r.raise_for_status()
    token = r.json().get("authToken")
    if not token:
        return {"status": "waiting"}
    u = await http.get("https://plex.tv/api/v2/user", headers={**PLEX_HEADERS, "X-Plex-Token": token})
    u.raise_for_status()
    user = u.json()
    name = (user.get("username") or user.get("title") or "").lower()
    if name not in ALLOWED:
        return JSONResponse({"status": "denied", "user": name}, status_code=403)
    if client == "android":   # the app keeps this and sends it as a Bearer token
        return {"status": "ok", "user": name, "thumb": user.get("thumb"),
                "token": sign({"u": name, "exp": time.time() + APP_SESSION_DAYS * 86400, "c": "android"})}
    response.set_cookie("mq_session", sign({"u": name, "exp": time.time() + SESSION_DAYS * 86400}),
                        max_age=SESSION_DAYS * 86400, httponly=True, secure=COOKIE_SECURE,
                        samesite="lax", path=BASE or "/")
    return {"status": "ok", "user": name, "thumb": user.get("thumb")}


@auth.get("/me")
async def me(user=Depends(current_user)):
    return {"user": user["u"]}


@auth.post("/logout")
async def logout(response: Response):
    response.delete_cookie("mq_session", path=BASE or "/")
    return {"ok": True}


api = APIRouter(prefix=BASE + "/api", dependencies=[Depends(current_user)])


# ---------------------------------------------------------------- overview
@api.get("/overview")
async def overview():
    arrs = [n for n in ("sonarr", "radarr", "radarr4k", "lidarr", "prowlarr") if configured(n)]

    async def arr_summary(n):
        health = await safe(arr(n, "/health"), [])
        out = {"app": n, "label": LABEL[n], "link": LINKS[n],
               "health": [{"type": h.get("type"), "message": h.get("message")} for h in health
                          if isinstance(health, list) and h.get("type") in ("warning", "error")
                          and "Allowed Hosts" not in (h.get("message") or "")]}
        if n in ("sonarr", "radarr", "radarr4k", "lidarr"):
            q = await safe(arr(n, "/queue", params={"pageSize": 1}), {})
            out["queue"] = q.get("totalRecords", 0) if isinstance(q, dict) else 0
            if n != "lidarr":
                m = await safe(arr(n, "/wanted/missing", params={"pageSize": 1, "monitored": "true"}), {})
                out["missing"] = m.get("totalRecords", 0) if isinstance(m, dict) else 0
        return out

    async def disks():
        out = []
        # STORAGE_PATHS="UNAS=/unas,..." - mounts measured directly (Sonarr doesn't report network shares)
        for spec in filter(None, os.environ.get("STORAGE_PATHS", "").split(",")):
            label, _, path = spec.partition("=")
            try:
                u = shutil.disk_usage(path)
                out.append({"path": path, "label": label, "free": u.free, "total": u.total})
            except OSError:
                pass
        if not configured("sonarr"):
            return out
        d = await safe(arr("sonarr", "/diskspace"), [])
        seen = {x["total"] for x in out}
        for x in d if isinstance(d, list) else []:
            if x.get("totalSpace", 0) < 50 * 2**30 or x["totalSpace"] in seen:
                continue
            seen.add(x["totalSpace"])
            out.append({"path": x.get("path"), "label": x.get("label") or ("jerry (system SSD)" if x.get("path") == "/" else x.get("path")),
                        "free": x.get("freeSpace", 0), "total": x.get("totalSpace", 0)})
        return out

    async def playing():
        if not configured("tautulli"):
            return []
        a = await safe(tautulli("get_activity"), {})
        return [{"user": s.get("friendly_name"), "title": s.get("full_title"), "progress": s.get("progress_percent"),
                 "state": s.get("state"), "decision": s.get("transcode_decision"),
                 "quality": s.get("quality_profile"), "thumb": None}
                for s in (a.get("sessions", []) if isinstance(a, dict) else [])]

    async def pending():
        if not configured("seerr"):
            return 0
        c = await safe(seerr("/request/count"), {})
        return c.get("pending", 0) if isinstance(c, dict) else 0

    summaries, disk, now, pend = await asyncio.gather(
        asyncio.gather(*(arr_summary(n) for n in arrs)), disks(), playing(), pending())
    return {"apps": summaries, "disks": disk, "playing": now, "pendingRequests": pend,
            "links": {n: LINKS[n] for n in SERVICES if LINKS[n]}}


# ---------------------------------------------------------------- queue (kept per app, tagged)
def _queue_item(n, q):
    if n == "sonarr":
        s, e = q.get("series") or {}, q.get("episode") or {}
        title = s.get("title") or q.get("title")
        sub = f"S{e.get('seasonNumber', 0):02}E{e.get('episodeNumber', 0):02} · {e.get('title', '')}" if e else ""
        art = poster(s.get("images"))
    else:
        m = q.get("movie") or {}
        title = m.get("title") or q.get("title")
        sub = str(m.get("year") or "")
        art = poster(m.get("images"))
    size, left = q.get("size") or 0, q.get("sizeleft") or 0
    msgs = [x for sm in q.get("statusMessages") or [] for x in sm.get("messages", [])]
    return {"app": n, "label": LABEL[n], "id": q["id"], "title": title, "sub": sub, "poster": art,
            "release": q.get("title"), "quality": ((q.get("quality") or {}).get("quality") or {}).get("name"),
            "progress": round(100 * (1 - left / size), 1) if size else 0, "size": size,
            "timeleft": q.get("timeleft"), "status": q.get("status"),
            "state": q.get("trackedDownloadState"), "health": q.get("trackedDownloadStatus"),
            "messages": msgs[:3] or ([q["errorMessage"]] if q.get("errorMessage") else []),
            "client": q.get("downloadClient"), "protocol": q.get("protocol"),
            "episodeId": (q.get("episode") or {}).get("id"), "movieId": q.get("movieId")}


@api.get("/queue")
async def queue():
    out = {}
    for n in ("sonarr", "radarr", "radarr4k"):
        if not configured(n):
            continue
        params = {"pageSize": 500, "includeUnknownSeriesItems": "true"}
        params.update({"includeSeries": "true", "includeEpisode": "true"} if n == "sonarr" else {"includeMovie": "true"})
        q = await safe(arr(n, "/queue", params=params), {})
        out[n] = [_queue_item(n, x) for x in q.get("records", [])] if "records" in q else q
    return out


@api.post("/queue/{name}/{qid}/remove")
async def queue_remove(name: str, qid: int, blocklist: bool = True, search: bool = False):
    if name not in ("sonarr", "radarr", "radarr4k"):
        raise HTTPException(404)
    await arr(name, f"/queue/{qid}", "DELETE",
              params={"removeFromClient": "true", "blocklist": str(blocklist).lower(),
                      "skipRedownload": str(not search).lower()})
    return {"ok": True}


@api.post("/search/{name}")
async def search(name: str, body: dict):
    """Search one thing, never the whole library (bulk searches starve the arr task slots)."""
    if name == "sonarr" and body.get("episodeIds"):
        cmd = {"name": "EpisodeSearch", "episodeIds": body["episodeIds"][:50]}
    elif name in ("radarr", "radarr4k") and body.get("movieIds"):
        cmd = {"name": "MoviesSearch", "movieIds": body["movieIds"][:50]}
    else:
        raise HTTPException(400, "episodeIds or movieIds required")
    r = await arr(name, "/command", "POST", body=cmd)
    return {"ok": True, "command": r.get("id")}


# ---------------------------------------------------------------- calendar
@api.get("/calendar")
async def calendar(days: int = 14):
    start = datetime.now(timezone.utc).date() - timedelta(days=1)
    end = start + timedelta(days=days + 1)
    rng = {"start": start.isoformat(), "end": end.isoformat()}
    items = []
    if configured("sonarr"):
        for e in await safe(arr("sonarr", "/calendar", params={**rng, "includeSeries": "true"}), []):
            if not isinstance(e, dict):
                continue
            s = e.get("series") or {}
            items.append({"app": "sonarr", "label": "Sonarr", "when": e.get("airDateUtc"), "title": s.get("title"),
                          "sub": f"S{e.get('seasonNumber', 0):02}E{e.get('episodeNumber', 0):02} · {e.get('title', '')}",
                          "hasFile": e.get("hasFile"), "monitored": e.get("monitored"),
                          "poster": poster(s.get("images")), "network": s.get("network")})
    for n in ("radarr", "radarr4k"):
        if not configured(n):
            continue
        for m in await safe(arr(n, "/calendar", params=rng), []):
            if not isinstance(m, dict):
                continue
            for kind, key in (("Digital", "digitalRelease"), ("Physical", "physicalRelease"), ("Cinemas", "inCinemas")):
                if m.get(key) and rng["start"] <= m[key][:10] <= rng["end"]:
                    items.append({"app": n, "label": LABEL[n], "when": m[key], "title": m.get("title"),
                                  "sub": f"{kind} release · {m.get('year')}", "hasFile": m.get("hasFile"),
                                  "monitored": m.get("monitored"), "poster": poster(m.get("images"))})
    items.sort(key=lambda x: x["when"] or "")
    return {"items": items}


# ---------------------------------------------------------------- seerr requests
STATUS = {1: "pending", 2: "approved", 3: "declined", 4: "failed", 5: "completed"}
MEDIA_STATUS = {1: "unknown", 2: "pending", 3: "processing", 4: "partially available", 5: "available", 6: "blocklisted", 7: "deleted"}
_titles: dict = {}


async def _title(media):
    key = (media.get("mediaType"), media.get("tmdbId"))
    if key not in _titles:
        d = await safe(seerr(f"/{'movie' if key[0] == 'movie' else 'tv'}/{key[1]}"), {})
        _titles[key] = {"title": d.get("title") or d.get("name") or f"TMDB {key[1]}",
                        "year": (d.get("releaseDate") or d.get("firstAirDate") or "")[:4],
                        "poster": f"https://image.tmdb.org/t/p/w185{d['posterPath']}" if d.get("posterPath") else None}
    return _titles[key]


@api.get("/requests")
async def requests(filter: str = "all", take: int = 30):
    if not configured("seerr"):
        return {"items": []}
    r = await seerr("/request", params={"take": take, "skip": 0, "filter": filter, "sort": "added"})
    out = []
    for q in r.get("results", []):
        media = q.get("media") or {}
        t = await _title(media)
        out.append({"id": q["id"], "type": q.get("type"), "status": STATUS.get(q.get("status"), "?"),
                    "mediaStatus": MEDIA_STATUS.get(media.get("status"), "?"), "is4k": q.get("is4k"),
                    "by": (q.get("requestedBy") or {}).get("displayName"), "created": q.get("createdAt"),
                    "seasons": [s.get("seasonNumber") for s in q.get("seasons") or []], **t})
    return {"items": out, "total": (r.get("pageInfo") or {}).get("results")}


@api.post("/requests/{rid}/{action}")
async def request_action(rid: int, action: str):
    if action not in ("approve", "decline"):
        raise HTTPException(400)
    await seerr(f"/request/{rid}/{action}", "POST")
    return {"ok": True}


# ---------------------------------------------------------------- activity + library
@api.get("/activity")
async def activity():
    if not configured("tautulli"):
        return {"playing": [], "history": []}
    a, h = await asyncio.gather(safe(tautulli("get_activity"), {}), safe(tautulli("get_history", length=25), {}))
    playing = [{"user": s.get("friendly_name"), "title": s.get("full_title"), "progress": s.get("progress_percent"),
                "state": s.get("state"), "decision": s.get("transcode_decision"), "player": s.get("player"),
                "quality": s.get("quality_profile")} for s in (a.get("sessions", []) if isinstance(a, dict) else [])]
    hist = [{"user": x.get("friendly_name"), "title": x.get("full_title"), "when": x.get("date"),
             "watched": x.get("watched_status"), "duration": x.get("play_duration")}
            for x in (h.get("data", []) if isinstance(h, dict) else [])]
    return {"playing": playing, "history": hist}


@api.get("/library")
async def library():
    out = {"recent": [], "missing": {}}

    async def recent(n):
        if not configured(n):
            return []
        params = {"pageSize": 20, "sortKey": "date", "sortDirection": "descending", "eventType": 3}
        params.update({"includeSeries": "true", "includeEpisode": "true"} if n == "sonarr" else {"includeMovie": "true"})
        h = await safe(arr(n, "/history", params=params), {})
        items = []
        for x in h.get("records", []) if isinstance(h, dict) else []:
            if n == "sonarr":
                s, e = x.get("series") or {}, x.get("episode") or {}
                items.append({"app": n, "label": LABEL[n], "when": x.get("date"), "title": s.get("title"),
                              "sub": f"S{e.get('seasonNumber', 0):02}E{e.get('episodeNumber', 0):02} · {e.get('title', '')}",
                              "quality": ((x.get("quality") or {}).get("quality") or {}).get("name"),
                              "poster": poster(s.get("images"))})
            else:
                m = x.get("movie") or {}
                items.append({"app": n, "label": LABEL[n], "when": x.get("date"), "title": m.get("title"),
                              "sub": str(m.get("year") or ""),
                              "quality": ((x.get("quality") or {}).get("quality") or {}).get("name"),
                              "poster": poster(m.get("images"))})
        return items

    async def missing(n):
        if not configured(n):
            return None
        m = await safe(arr(n, "/wanted/missing", params={"pageSize": 1, "monitored": "true"}), {})
        c = await safe(arr(n, "/wanted/cutoff", params={"pageSize": 1, "monitored": "true"}), {})
        return {"missing": m.get("totalRecords"), "cutoff": c.get("totalRecords")}

    names = ("sonarr", "radarr", "radarr4k")
    rec, mis = await asyncio.gather(asyncio.gather(*(recent(n) for n in names)),
                                    asyncio.gather(*(missing(n) for n in names)))
    out["recent"] = sorted([i for r in rec for i in r], key=lambda x: x["when"] or "", reverse=True)[:40]
    out["missing"] = {n: v for n, v in zip(names, mis) if v}
    return out


@api.get("/links")
async def links():
    return {n: {"label": LABEL[n], "url": LINKS[n]} for n in SERVICES if LINKS[n]}


# ---------------------------------------------------------------- routers
from server import appdist, assistant, media  # noqa: E402  (they import server.core, not this module)

app.include_router(auth)
app.include_router(api)
app.include_router(media.router)
app.include_router(assistant.router)
app.include_router(appdist.router)


@app.get("/")
async def root():
    return RedirectResponse(BASE + "/")


@app.get(BASE)
async def base_noslash():
    return RedirectResponse(BASE + "/")


@app.get(BASE + "/healthz")
async def healthz():
    return {"ok": True, "services": {n: configured(n) for n in SERVICES}, "assistant": assistant.enabled()}


app.mount(BASE, StaticFiles(directory=WEB, html=True), name="web")


@app.exception_handler(httpx.HTTPStatusError)
async def upstream_error(request: Request, exc: httpx.HTTPStatusError):
    return JSONResponse({"error": f"upstream {exc.response.status_code}", "url": str(exc.request.url).split("?")[0]},
                        status_code=502)
