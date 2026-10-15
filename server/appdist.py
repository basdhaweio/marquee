"""Self-update for the Android app: the build drops marquee.apk + latest.json into DATA/app/.

latest.json = {"versionCode": 3, "versionName": "1.0.2", "notes": "..."}. Both endpoints need sign-in.
"""
import json

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse

from server.core import BASE, DATA, current_user

router = APIRouter(prefix=BASE + "/app", dependencies=[Depends(current_user)])
APP_DIR = DATA / "app"


@router.get("/latest")
async def latest():
    f = APP_DIR / "latest.json"
    if not f.exists() or not (APP_DIR / "marquee.apk").exists():
        return {"versionCode": 0}
    return {**json.loads(f.read_text()), "url": BASE + "/app/marquee.apk"}


@router.get("/marquee.apk")
async def apk():
    f = APP_DIR / "marquee.apk"
    if not f.exists():
        raise HTTPException(404)
    return FileResponse(f, media_type="application/vnd.android.package-archive", filename="marquee.apk")
