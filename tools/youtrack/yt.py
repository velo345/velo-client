"""Tiny YouTrack REST helper (token from ../../.env YOUTRACK_TOKEN; never printed)."""
import json
import os
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.environ.get("YOUTRACK_URL", "https://velo-client.youtrack.cloud")


TOKEN_KEY = os.environ.get("YOUTRACK_TOKEN_KEY", "YOUTRACK_TOKEN")


def token():
    for line in open(os.path.join(ROOT, ".env"), encoding="utf-8"):
        if line.startswith(TOKEN_KEY + "="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("YOUTRACK_TOKEN missing in .env")


TOKEN = token()


def call(method, path, body=None, fields=None, query=""):
    url = BASE + "/api/" + path.lstrip("/")
    params = []
    if fields:
        params.append("fields=" + fields)
    if query:
        params.append(query)
    if params:
        url += ("&" if "?" in url else "?") + "&".join(params)
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + TOKEN, "Accept": "application/json", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def get(path, fields=None, query=""):
    return call("GET", path, None, fields, query)


def post(path, body, fields=None, query=""):
    return call("POST", path, body, fields, query)
