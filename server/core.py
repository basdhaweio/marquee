"""Shared plumbing: configuration, sessions, and server-side clients for every media-stack service.

API keys never leave this process. Browsers authenticate with the `mq_session` cookie; the native
Android app sends the same signed token as `Authorization: Bearer <token>`.
"""
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path

import httpx
from fastapi import HTTPException, Request

BASE = os.environ.get("BASE_PATH", "/hub").rstrip("/")
SECRET = os.environ.get("SESSION_SECRET") or "dev-only-secret"
ALLOWED = {u.strip().lower() for u in os.environ.get("ALLOWED_PLEX_USERS", "").split(",") if u.strip()}
CLIENT_ID = os.environ.get("PLEX_CLIENT_ID") or str(uuid.uuid5(uuid.NAMESPACE_URL, "marquee:" + SECRET))
COOKIE_SECURE = os.environ.get("COOKIE_SECURE", "true").lower() != "false"
DEV_NO_AUTH = os.environ.get("DEV_NO_AUTH") == "1"  # local testing only
SESSION_DAYS = 30
APP_SESSION_DAYS = 180  # the phone app stays signed in longer
WEB = Path(os.environ.get("WEB_DIR", Path(__file__).resolve().parent.parent / "web"))
DATA = Path(os.environ.get("DATA_DIR", Path(__file__).resolve().parent.parent / "data"))

# Services: URL includes any URL base; key comes from env. Missing ones are simply skipped.
SERVICES = {
    "sonarr": ("SONARR_URL", "SONARR_KEY"),
    "radarr": ("RADARR_URL", "RADARR_KEY"),
    "radarr4k": ("RADARR4K_URL", "RADARR4K_KEY"),
    "lidarr": ("LIDARR_URL", "LIDARR_KEY"),
    "prowlarr": ("PROWLARR_URL", "PROWLARR_KEY"),
    "seerr": ("SEERR_URL", "SEERR_KEY"),
    "tautulli": ("TAUTULLI_URL", "TAUTULLI_KEY"),
}
CFG = {n: (os.environ.get(u, "").rstrip("/"), os.environ.get(k, "")) for n, (u, k) in SERVICES.items()}
ARR_API = {"sonarr": "v3", "radarr": "v3", "radarr4k": "v3", "lidarr": "v1", "prowlarr": "v1"}
LABEL = {"sonarr": "Sonarr", "radarr": "Radarr", "radarr4k": "Radarr 4K", "lidarr": "Lidarr",
         "prowlarr": "Prowlarr", "seerr": "Seerr", "tautulli": "Tautulli"}
# Public links shown in the app (open the real UI). Override per service with <NAME>_LINK.
LINKS = {n: os.environ.get(f"{n.upper()}_LINK", "") for n in SERVICES}
MOVIE_APPS = ("radarr", "radarr4k")

http = httpx.AsyncClient(timeout=20.0, follow_redirects=True)


def configured(name):
    url, key = CFG[name]
    return bool(url and key)


# ---------------------------------------------------------------- sessions
def sign(payload: dict) -> str:
    body = base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()
    mac = hmac.new(SECRET.encode(), body.encode(), hashlib.sha256).hexdigest()
    return f"{body}.{mac}"


def verify(token: str):
    try:
        body, mac = token.rsplit(".", 1)
        if not hmac.compare_digest(mac, hmac.new(SECRET.encode(), body.encode(), hashlib.sha256).hexdigest()):
            return None
        data = json.loads(base64.urlsafe_b64decode(body))
        return data if data.get("exp", 0) > time.time() else None
    except Exception:
        return None


def current_user(request: Request):
    if DEV_NO_AUTH:
        return {"u": "dev"}
    token = request.cookies.get("mq_session", "")
    bearer = request.headers.get("authorization", "")
    if bearer.lower().startswith("bearer "):
        token = bearer[7:].strip()
    data = verify(token)
    if not data:
        raise HTTPException(401, "sign in required")
    return data


# ---------------------------------------------------------------- service clients
async def arr(name, path, method="GET", params=None, body=None):
    url, key = CFG[name]
    r = await http.request(method, f"{url}/api/{ARR_API[name]}{path}", params=params, json=body,
                           headers={"X-Api-Key": key})
    r.raise_for_status()
    return r.json() if r.content else {}


async def seerr(path, method="GET", params=None, body=None):
    url, key = CFG["seerr"]
    r = await http.request(method, f"{url}/api/v1{path}", params=params, json=body, headers={"X-Api-Key": key})
    r.raise_for_status()
    return r.json() if r.content else {}


async def tautulli(cmd, **params):
    url, key = CFG["tautulli"]
    r = await http.get(f"{url}/api/v2", params={"apikey": key, "cmd": cmd, **params})
    r.raise_for_status()
    return r.json()["response"]["data"]


async def safe(coro, default=None):
    """Never let one service being down break a whole view."""
    try:
        return await coro
    except Exception as e:  # noqa: BLE001
        return {"_error": f"{type(e).__name__}: {str(e)[:160]}"} if default is None else default


def poster(images, kind="poster"):
    for i in images or []:
        if i.get("coverType") == kind:
            return i.get("remoteUrl") or i.get("url")
    return None


def quality_name(q):
    return ((q or {}).get("quality") or {}).get("name")
