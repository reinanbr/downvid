# Resident yt-dlp extractor used by YtDlpServer.kt.
#
# Importing Python + yt-dlp costs ~7 s on a phone; this process pays it once
# and then answers extraction requests over stdin/stdout (one JSON object per
# line), each handled in its own thread.
#
#   argv[1]  path of the yt-dlp zipapp (importable via zipimport)
#   request  {"id": "...", "url": "...", "cacheDir": "...", "qjs": "/path/qjs",
#             "flat": bool, "playlistEnd": n}
#   reply    {"id": "...", "json": "<info dict>", "stderr": "...", "exitCode": 0|1}
import json
import sys
import threading

if len(sys.argv) > 1 and sys.argv[1]:
    sys.path.insert(0, sys.argv[1])

import yt_dlp  # noqa: E402

_out = threading.Lock()


def send(obj):
    line = json.dumps(obj)
    with _out:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


class Logger:
    """Collects warnings/errors in yt-dlp's CLI format (parsed by the Go core)."""

    def __init__(self):
        self.lines = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        self.lines.append(msg if msg.startswith("WARNING") else "WARNING: " + msg)

    def error(self, msg):
        self.lines.append(msg if msg.startswith("ERROR") else "ERROR: " + msg)


def handle(req):
    log = Logger()
    opts = {
        "quiet": True,
        "noprogress": True,
        "noplaylist": True,
        "skip_download": True,
        "logger": log,
        "socket_timeout": 20,
        "nocheckcertificate": True,
        "cachedir": req.get("cacheDir") or False,
    }
    if req.get("qjs"):
        opts["js_runtimes"] = {"quickjs": {"path": req["qjs"]}}
    if req.get("flat"):
        # Search pages: list entries without extracting each one.
        opts.update({"extract_flat": "in_playlist", "noplaylist": False,
                     "playlistend": int(req.get("playlistEnd") or 5)})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.sanitize_info(ydl.extract_info(req["url"], download=False))
        send({"id": req["id"], "json": json.dumps(info), "stderr": "\n".join(log.lines), "exitCode": 0})
    except BaseException as e:  # DownloadError, ExtractorError, anything else
        msg = str(e)
        if not msg.startswith("ERROR"):
            msg = "ERROR: " + msg
        send({"id": req["id"], "json": "", "stderr": "\n".join(log.lines + [msg]), "exitCode": 1})


send({"ready": True, "version": yt_dlp.version.__version__})
for raw in sys.stdin:
    raw = raw.strip()
    if raw:
        threading.Thread(target=handle, args=(json.loads(raw),), daemon=True).start()
