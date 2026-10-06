# Marquee

One admin hub for the media stack on jerry-EQ — queues (tagged Sonarr / Radarr / Radarr 4K),
calendar, Seerr requests, Tautulli activity, library gaps, health and disk — in the same
vanilla-JS PWA style as Forge and Canto.

Unlike those, Marquee has a small server: the browser never sees an API key. `server/app.py`
(FastAPI) talks to each service and hands the PWA curated JSON. Access is **Plex sign-in**,
limited to the usernames in `ALLOWED_PLEX_USERS`. Household requests stay in Seerr.

## Layout
- `server/app.py` — auth (Plex PIN), service clients, `/hub/api/*`
- `web/` — the PWA (`index.html`, `css/app.css`, `js/{api,ui,views,app}.js`, `sw.js`)
- `deploy/docker-compose.yml` — the Portainer stack (secrets come from stack env vars, never the repo)
- The image is built on jerry straight from this repo:
  `docker build -t marquee:latest https://github.com/basdhaweio/marquee.git#main`, then redeploy the stack.
  `deploy/github-workflow-docker.yml` is a ready CI workflow (move it to `.github/workflows/` once
  `gh auth refresh -s workflow` has been run) if we want GHCR images + Watchtower updates later.

## Deploy
Runs on jerry with `network_mode: host`, listening on `127.0.0.1:8890` only, served publicly
through Tailscale Funnel at `/hub`:

```
tailscale funnel --bg --https=443 --set-path /hub http://127.0.0.1:8890/hub
```

## Local dev
`DEV_NO_AUTH=1 COOKIE_SECURE=false` plus the `*_URL` / `*_KEY` env vars, then
`uvicorn server.app:app --port 8890` and open http://localhost:8890/hub/.
