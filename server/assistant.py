"""Claude, inside Marquee: a chat assistant that can look things up and act on the media stack.

Read-only tools run immediately. Tools that change something (add, search, grab, approve, remove…) pause
the turn: the app shows an approval card, and the conversation continues only after John approves or
declines (POST /assistant/{id}/decide). That pause spans HTTP requests, so this is a manual tool loop with
the conversation persisted on disk, not the SDK tool runner.

Needs ANTHROPIC_API_KEY in the environment; without it the assistant reports itself disabled.
"""
import asyncio
import json
import os
import time
import uuid

import anthropic
from fastapi import APIRouter, Depends, HTTPException

from server import media
from server.core import BASE, DATA, current_user

MODEL = os.environ.get("ASSISTANT_MODEL", "claude-sonnet-5-5")   # cheaper than Opus; plenty for admin chores
EFFORT = os.environ.get("ASSISTANT_EFFORT", "medium")
MAX_STEPS = 16
RESULT_CHARS = 14000
STORE = DATA / "assistant"
router = APIRouter(prefix=BASE + "/api/assistant", dependencies=[Depends(current_user)])
_client = None
_locks: dict[str, asyncio.Lock] = {}


def enabled():
    return bool(os.environ.get("ANTHROPIC_API_KEY"))


def client():
    global _client
    if _client is None:
        _client = anthropic.AsyncAnthropic()
    return _client


SYSTEM = """You are the assistant inside Marquee, John's admin app for his home media server (host "jerry").
You manage it through tools that call Sonarr (TV), Radarr (1080p movies), Radarr 4K, Prowlarr (indexers),
Seerr (requests from household members) and Tautulli (Plex playback stats).

How to work:
- Look before you act: find the exact series/movie (and ids) with the read tools first. Never guess an id.
- Tools that change things are shown to John as an approval card before they run; just call them with the exact
  arguments you intend. If he declines, accept it and ask what he'd prefer.
- Keep searches targeted (a few items at a time). Never trigger a search across a whole library.
- Defaults: movies go to Radarr in 1080p; use Radarr 4K only when he asks for 4K. New shows: monitor all, search on add.
- You can't delete files or empty trash; say so if asked, and point him to the right app.
- House rules: Iron Chef's dubbed episodes are deliberately unmonitored (only the Food Network dub counts) - never re-monitor
  or search them. For anime he usually wants uncensored releases; mention it if a release looks censored.
- Answers are read on a phone: short, scannable, no tables wider than three columns. Lead with the answer or the
  outcome, then the detail he needs."""


def _obj(props, required=()):
    return {"type": "object", "properties": props, "required": list(required)}


APP_MOVIE = {"type": "string", "enum": ["radarr", "radarr4k"], "description": "radarr = 1080p library, radarr4k = 4K library"}
APP_ANY = {"type": "string", "enum": ["sonarr", "radarr", "radarr4k"]}
IDS = {"type": "array", "items": {"type": "integer"}}


async def _overview():
    from server import app as core_app
    return await core_app.overview()


async def _queue():
    from server import app as core_app
    return await core_app.queue()


async def _calendar(days=14):
    from server import app as core_app
    return await core_app.calendar(days)


async def _requests(filter="all"):
    from server import app as core_app
    return await core_app.requests(filter, 30)


async def _activity():
    from server import app as core_app
    return await core_app.activity()


async def _request_action(request_id, action):
    from server import app as core_app
    return await core_app.request_action(request_id, action)


async def _remove_queue(app, queue_id, blocklist=True, search=False):
    from server import app as core_app
    return await core_app.queue_remove(app, queue_id, blocklist, search)


# name -> (description, schema, function, changes_something, how to describe it on the approval card)
TOOLS = {
    "get_overview": ("Health of every service, queue/missing counts, disk space, what's playing, pending requests.",
                     _obj({}), lambda **a: _overview(), False, None),
    "get_queue": ("Current downloads per app (Sonarr, Radarr, Radarr 4K) with progress, status and any warnings.",
                  _obj({}), lambda **a: _queue(), False, None),
    "get_calendar": ("Upcoming episodes and movie releases.", _obj({"days": {"type": "integer"}}),
                     lambda days=14, **a: _calendar(days), False, None),
    "list_shows": ("Shows in the Sonarr library, optionally filtered by a title substring.",
                   _obj({"query": {"type": "string"}}), lambda query="", **a: media.list_shows(query), False, None),
    "get_show": ("One show with every season/episode: monitored, has file, quality, language, release group.",
                 _obj({"series_id": {"type": "integer"}}, ["series_id"]),
                 lambda series_id, **a: media.get_show(series_id), False, None),
    "list_movies": ("Movies in a Radarr library, optionally filtered by a title substring.",
                    _obj({"app": APP_MOVIE, "query": {"type": "string"}}, ["app"]),
                    lambda app, query="", **a: media.list_movies(app, query), False, None),
    "get_movie": ("One movie's details and file.", _obj({"app": APP_MOVIE, "movie_id": {"type": "integer"}}, ["app", "movie_id"]),
                  lambda app, movie_id, **a: media.get_movie(app, movie_id), False, None),
    "lookup": ("Search TVDB/TMDB for a show or movie to add (also says whether it's already in the library).",
               _obj({"kind": {"type": "string", "enum": ["show", "movie"]}, "term": {"type": "string"}}, ["kind", "term"]),
               lambda kind, term, **a: media.lookup(kind, term), False, None),
    "get_options": ("Quality profiles and root folders for an app (needed only to override add defaults).",
                    _obj({"app": APP_ANY}, ["app"]), lambda app, **a: media.options(app), False, None),
    "find_releases": ("Interactive search: releases the indexers offer for ONE episode (sonarr) or ONE movie, best first.",
                      _obj({"app": APP_ANY, "episode_id": {"type": "integer"}, "movie_id": {"type": "integer"}}, ["app"]),
                      lambda app, episode_id=None, movie_id=None, **a: media.releases(app, episode_id, movie_id), False, None),
    "get_history": ("Recent grabs/imports/failures for an app.", _obj({"app": APP_ANY}, ["app"]),
                    lambda app, **a: media.history(app), False, None),
    "get_requests": ("Seerr requests from household members.",
                     _obj({"filter": {"type": "string", "enum": ["all", "pending", "approved", "processing", "available"]}}),
                     lambda filter="all", **a: _requests(filter), False, None),
    "get_activity": ("What's playing on Plex right now and recent watch history (Tautulli).", _obj({}),
                     lambda **a: _activity(), False, None),
    "watch_stats": ("Most watched shows/movies, most active users and platforms over N days (Tautulli).",
                    _obj({"days": {"type": "integer"}}), lambda days=30, **a: media.watch_stats(days), False, None),
    "list_indexers": ("Prowlarr indexers: enabled, failing, priority.", _obj({}), lambda **a: media.indexers(), False, None),
    "indexer_search": ("Free-text search across all Prowlarr indexers (to see what's out there).",
                       _obj({"query": {"type": "string"}}, ["query"]), lambda query, **a: media.indexer_search(query), False, None),
    "test_indexer": ("Test one Prowlarr indexer's connection.", _obj({"indexer_id": {"type": "integer"}}, ["indexer_id"]),
                     lambda indexer_id, **a: media.test_indexer(indexer_id), False, None),
    # ---- these change something: John approves each call in the app
    "add_show": ("Add a show to Sonarr (by TVDB id from lookup). Uses the library's usual profile/folder unless given.",
                 _obj({"tvdb_id": {"type": "integer"}, "monitor": {"type": "string", "enum": ["all", "future", "missing", "none"]},
                       "search": {"type": "boolean"}, "profile_id": {"type": "integer"},
                       "series_type": {"type": "string", "enum": ["standard", "anime", "daily"]}}, ["tvdb_id"]),
                 lambda tvdb_id, monitor="all", search=True, profile_id=None, series_type="standard", **a:
                 media.add_show(tvdb_id, profile_id, None, monitor, search, series_type), True,
                 lambda a: f"Add show (TVDB {a.get('tvdb_id')}) to Sonarr, monitor {a.get('monitor', 'all')}"
                           f"{', search now' if a.get('search', True) else ''}"),
    "add_movie": ("Add a movie to Radarr or Radarr 4K (by TMDB id from lookup) and optionally search.",
                  _obj({"app": APP_MOVIE, "tmdb_id": {"type": "integer"}, "search": {"type": "boolean"},
                        "profile_id": {"type": "integer"}}, ["app", "tmdb_id"]),
                  lambda app, tmdb_id, search=True, profile_id=None, **a: media.add_movie(app, tmdb_id, profile_id, None, search),
                  True, lambda a: f"Add movie (TMDB {a.get('tmdb_id')}) to {'Radarr 4K' if a.get('app') == 'radarr4k' else 'Radarr'}"
                                  f"{', search now' if a.get('search', True) else ''}"),
    "set_monitored": ("Monitor/unmonitor shows or movies (level=item) or Sonarr episodes (level=episode).",
                      _obj({"app": APP_ANY, "ids": IDS, "monitored": {"type": "boolean"},
                            "level": {"type": "string", "enum": ["item", "episode"]}}, ["app", "ids", "monitored"]),
                      lambda app, ids, monitored, level="item", **a: media.set_monitored(app, ids, monitored, level), True,
                      lambda a: f"{'Monitor' if a.get('monitored') else 'Unmonitor'} {len(a.get('ids', []))} "
                                f"{'episode(s)' if a.get('level') == 'episode' else 'item(s)'} in {a.get('app')}"),
    "search_now": ("Ask Sonarr (episode ids) or Radarr (movie ids) to search for these now. Max 50 ids.",
                   _obj({"app": APP_ANY, "ids": IDS}, ["app", "ids"]),
                   lambda app, ids, **a: media.run_search(app, ids), True,
                   lambda a: f"Search {a.get('app')} for {len(a.get('ids', []))} item(s) now"),
    "grab_release": ("Download one specific release found with find_releases.",
                     _obj({"app": APP_ANY, "guid": {"type": "string"}, "indexer_id": {"type": "integer"},
                           "title": {"type": "string", "description": "release title, for John's approval card"}},
                          ["app", "guid", "indexer_id", "title"]),
                     lambda app, guid, indexer_id, **a: media.grab(app, guid, indexer_id), True,
                     lambda a: f"Grab in {a.get('app')}: {a.get('title', a.get('guid'))}"),
    "remove_download": ("Remove an item from an app's download queue, optionally blocklisting the release and searching again.",
                        _obj({"app": APP_ANY, "queue_id": {"type": "integer"}, "blocklist": {"type": "boolean"},
                              "search_again": {"type": "boolean"}, "title": {"type": "string"}}, ["app", "queue_id", "title"]),
                        lambda app, queue_id, blocklist=True, search_again=False, **a: _remove_queue(app, queue_id, blocklist, search_again),
                        True, lambda a: f"Remove from {a.get('app')} queue: {a.get('title')}"
                                        f"{' (blocklist)' if a.get('blocklist', True) else ''}{', search again' if a.get('search_again') else ''}"),
    "decide_request": ("Approve or decline a household member's Seerr request.",
                       _obj({"request_id": {"type": "integer"}, "action": {"type": "string", "enum": ["approve", "decline"]},
                             "title": {"type": "string"}}, ["request_id", "action", "title"]),
                       lambda request_id, action, **a: _request_action(request_id, action), True,
                       lambda a: f"{a.get('action', '').capitalize()} Seerr request: {a.get('title')}"),
    "request_media": ("Create a Seerr request (useful for 4K requests or on someone's behalf).",
                      _obj({"kind": {"type": "string", "enum": ["show", "movie"]}, "tmdb_id": {"type": "integer"},
                            "is4k": {"type": "boolean"}, "title": {"type": "string"}}, ["kind", "tmdb_id", "title"]),
                      lambda kind, tmdb_id, is4k=False, **a: media.request_media(kind, tmdb_id, is4k), True,
                      lambda a: f"Request in Seerr: {a.get('title')}{' (4K)' if a.get('is4k') else ''}"),
}
TOOL_DEFS = [{"name": n, "description": d, "input_schema": s} for n, (d, s, *_r) in TOOLS.items()]


# ---------------------------------------------------------------- storage
def _path(cid):
    if not cid.replace("-", "").isalnum():
        raise HTTPException(400)
    return STORE / f"{cid}.json"


def _load(cid):
    p = _path(cid)
    if not p.exists():
        raise HTTPException(404, "conversation not found")
    return json.loads(p.read_text())


def _save(c):
    STORE.mkdir(parents=True, exist_ok=True)
    c["updated"] = time.time()
    _path(c["id"]).write_text(json.dumps(c))


def _public(c):
    return {"id": c["id"], "title": c["title"], "updated": c.get("updated"), "display": c["display"],
            "pending": [{"id": p["id"], "tool": p["name"], "summary": p["summary"]} for p in c.get("pending", [])],
            "enabled": enabled()}


# ---------------------------------------------------------------- the loop
def _echoable(content):
    """Blocks to send back next turn. After a fallback, drop the declined partial's model-internal blocks."""
    last_fb = max((i for i, b in enumerate(content) if b.get("type") == "fallback"), default=-1)
    drop = {"thinking", "redacted_thinking", "tool_use", "server_tool_use"}
    return [b for i, b in enumerate(content) if not (i < last_fb and b.get("type") in drop)]


async def _run_tool(name, args):
    fn = TOOLS[name][2]
    try:
        out = await fn(**args)
        text = json.dumps(out, default=str)
        if len(text) > RESULT_CHARS:
            text = text[:RESULT_CHARS] + f"... [truncated, {len(text)} chars total - narrow the query]"
        return {"content": text}
    except HTTPException as e:
        return {"content": f"Error: {e.detail}", "is_error": True}
    except Exception as e:  # noqa: BLE001
        return {"content": f"Error: {type(e).__name__}: {str(e)[:300]}", "is_error": True}


async def _advance(c):
    """Call Claude until it finishes, or pauses on tools that need John's approval."""
    for _ in range(MAX_STEPS):
        try:
            resp = await client().beta.messages.create(
                model=MODEL, max_tokens=16000, system=SYSTEM, tools=TOOL_DEFS, messages=c["messages"],
                output_config={"effort": EFFORT}, cache_control={"type": "ephemeral"},
                betas=["server-side-fallback-2026-07-01"], fallbacks="default")
        except anthropic.AuthenticationError:
            c["display"].append({"role": "error", "text": "The Anthropic API key was rejected - check ANTHROPIC_API_KEY."})
            return
        except anthropic.RateLimitError:
            c["display"].append({"role": "error", "text": "Rate limited by the Claude API - try again in a minute."})
            return
        except anthropic.APIStatusError as e:
            c["display"].append({"role": "error", "text": f"Claude API error {e.status_code}: {str(e.message)[:200]}"})
            return
        except anthropic.APIConnectionError:
            c["display"].append({"role": "error", "text": "Couldn't reach the Claude API from jerry."})
            return

        content = [b.model_dump(mode="json", exclude_none=True) for b in resp.content]
        c["messages"].append({"role": "assistant", "content": _echoable(content)})
        text = "\n\n".join(b["text"] for b in content if b.get("type") == "text" and b.get("text"))
        if text:
            c["display"].append({"role": "assistant", "text": text})

        if resp.stop_reason == "refusal":
            c["display"].append({"role": "error", "text": "Claude declined that request."})
            return
        if resp.stop_reason == "max_tokens":
            c["display"].append({"role": "error", "text": "That answer hit the length limit - ask for less at once."})
            return
        calls = [b for b in content if b.get("type") == "tool_use"]
        if resp.stop_reason != "tool_use" or not calls:
            return

        results, pending = {}, []
        for call in calls:
            name, args = call["name"], call.get("input") or {}
            if name not in TOOLS:
                results[call["id"]] = {"content": f"Unknown tool {name}", "is_error": True}
            elif TOOLS[name][3]:
                pending.append({"id": call["id"], "name": name, "input": args, "summary": TOOLS[name][4](args)})
            else:
                c["display"].append({"role": "tool", "text": f"Checked: {name.replace('_', ' ')}"})
                results[call["id"]] = await _run_tool(name, args)
        if pending:
            c["pending"], c["pendingResults"], c["pendingOrder"] = pending, results, [x["id"] for x in calls]
            return
        c["messages"].append({"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": cid, **results[cid]} for cid in (x["id"] for x in calls)]})
    c["display"].append({"role": "error", "text": "Stopped after too many steps - try a narrower request."})


@router.get("")
async def conversations():
    STORE.mkdir(parents=True, exist_ok=True)
    out = []
    for p in sorted(STORE.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:50]:
        c = json.loads(p.read_text())
        out.append({"id": c["id"], "title": c["title"], "updated": c.get("updated"), "pending": bool(c.get("pending"))})
    return {"enabled": enabled(), "model": MODEL, "items": out}


@router.get("/{cid}")
async def conversation(cid: str):
    return _public(_load(cid))


@router.delete("/{cid}")
async def delete_conversation(cid: str):
    _path(cid).unlink(missing_ok=True)
    return {"ok": True}


@router.post("/chat")
async def chat(body: dict):
    if not enabled():
        raise HTTPException(503, "Assistant disabled: ANTHROPIC_API_KEY is not set on the server.")
    text = (body.get("message") or "").strip()
    if not text:
        raise HTTPException(400, "empty message")
    cid = body.get("conversationId")
    c = _load(cid) if cid else {"id": uuid.uuid4().hex, "title": text[:60], "messages": [], "display": [],
                                "created": time.time()}
    lock = _locks.setdefault(c["id"], asyncio.Lock())
    async with lock:
        if c.get("pending"):
            raise HTTPException(409, "Approve or decline the pending action first.")
        c["messages"].append({"role": "user", "content": text})
        c["display"].append({"role": "user", "text": text})
        await _advance(c)
        _save(c)
    return _public(c)


@router.post("/{cid}/decide")
async def decide(cid: str, body: dict):
    """approve: true/false applies to every pending action; or pass `approvals` {toolUseId: bool}."""
    c = _load(cid)
    lock = _locks.setdefault(cid, asyncio.Lock())
    async with lock:
        pending = c.get("pending") or []
        if not pending:
            return _public(c)
        approvals = body.get("approvals") or {p["id"]: bool(body.get("approve")) for p in pending}
        results = c.get("pendingResults", {})
        for p in pending:
            if approvals.get(p["id"]):
                c["display"].append({"role": "action", "text": f"Approved: {p['summary']}"})
                results[p["id"]] = await _run_tool(p["name"], p["input"])
            else:
                c["display"].append({"role": "action", "text": f"Declined: {p['summary']}"})
                results[p["id"]] = {"content": "John declined this action. Do not retry it unless he asks.", "is_error": True}
        order = c.get("pendingOrder") or list(results)
        c["messages"].append({"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": tid, **results[tid]} for tid in order if tid in results]})
        c["pending"], c["pendingResults"], c["pendingOrder"] = [], {}, []
        await _advance(c)
        _save(c)
    return _public(c)
