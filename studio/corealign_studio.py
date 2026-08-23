#!/usr/bin/env python3
"""CoreAlign Studio: run a TMA slide from a web page, with no desktop and no QuPath GUI.

The person opens one page, picks a slide, answers two questions, and presses Start. Everything
after that happens in a Slurm job on a compute node: QuPath runs headless, and the two review
gates are answered in the same page.

Why a proxy sits in the middle
------------------------------
Headless CoreAlign opens a loopback HTTP bridge on 127.0.0.1 inside the job. A reviewer's browser
is on their own laptop and can never reach that address, which is exactly why the desktop template
had to ship a browser inside the container. Studio removes that: the browser talks to Studio,
Studio talks to the bridge. The bridge token stays on the compute node and never reaches the
browser, so this is also less exposed than the desktop arrangement.

REPORT.html is reused as-is. CoreAlign already writes a good review page; Studio serves it with
the loopback URLs rewritten to its own proxy paths, so every control in it keeps working.

Standard library only, on purpose: the image should be QuPath plus a Python interpreter, nothing
that needs a package index at build time.
"""

from __future__ import annotations

import html
import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

PORT = int(os.environ.get("PORT") or os.environ.get("APPHUB_PORT") or 8848)
BIND = os.environ.get("COREALIGN_STUDIO_BIND", "0.0.0.0")
QUPATH = os.environ.get("COREALIGN_QUPATH", "/usr/local/bin/QuPath")
WORKFLOW = os.environ.get("COREALIGN_WORKFLOW", "/opt/corealign/CoreAlign.groovy")
MAX_BODY = 2 * 1024 * 1024

SLIDE_SUFFIXES = (
    ".ome.tif", ".ome.tiff", ".tif", ".tiff", ".svs", ".ndpi", ".czi",
    ".qptiff", ".scn", ".vsi", ".mrxs", ".bif", ".dcm",
)
# Names that are CoreAlign's own output, never a slide to open.
HIDDEN_DIRS = {"qc", "results", "work", "qupath", "node_modules", ".git", ".vnc", "__pycache__"}


def allowed_roots() -> list[Path]:
    """Folders the picker may show.

    COREALIGN_STUDIO_ROOTS wins when the operator configures it. Otherwise the person's own
    locker, plus any NAS share that is actually mounted, which is what the AppHub runner binds.
    """
    configured = os.environ.get("COREALIGN_STUDIO_ROOTS", "").strip()
    roots: list[Path] = []
    if configured:
        for entry in configured.split(":"):
            entry = entry.strip()
            if entry:
                roots.append(Path(entry))
    else:
        home = Path(os.environ.get("HOME", "/tmp"))
        roots.append(home)
        for share in ("/mnt/CRCproject", "/mnt/sisplockers", "/mnt/rarecyte-folder", "/mnt/allflash"):
            roots.append(Path(share))
    seen: list[Path] = []
    for root in roots:
        try:
            resolved = root.resolve()
        except OSError:
            continue
        if not resolved.is_dir() or resolved in seen:
            continue
        # The runner binds every NAS share the node mounts, including administrative ones
        # that have no business in a slide picker. Show only what this person can actually
        # open: the filesystem already decides that, so ask it rather than keeping a list.
        if not os.access(resolved, os.R_OK | os.X_OK):
            continue
        seen.append(resolved)
    return seen


ROOTS = allowed_roots()


def inside_roots(target: Path) -> bool:
    """True only when target really sits under an allowed root, symlinks resolved."""
    try:
        resolved = target.resolve()
    except OSError:
        return False
    for root in ROOTS:
        try:
            resolved.relative_to(root)
            return True
        except ValueError:
            continue
    return False


def is_slide(path: Path) -> bool:
    name = path.name.lower()
    return any(name.endswith(suffix) for suffix in SLIDE_SUFFIXES)


def human_size(num: int) -> str:
    step = float(num)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if step < 1024 or unit == "TB":
            return f"{step:.0f} {unit}" if unit == "B" else f"{step:.1f} {unit}"
        step /= 1024
    return f"{step:.1f} TB"


def build_config(tissue: str, output: str) -> dict:
    """The same two choices the desktop setup dialog asks, in the shape CoreAlign expects."""
    skin = tissue == "skin"
    return {
        "schemaVersion": 2,
        "activeProfile": "automatic",
        "profiles": {
            "automatic": {
                "description": (
                    "Automatic skin TMA with the epidermis at the top" if skin
                    else "Automatic TMA with a consistent tissue edge at the top"
                ),
                "grid": {
                    "geometryMode": "automatic",
                    "coreDiameterMode": "automatic",
                    "cropPaddingFactor": 1.9,
                    "autoDetectGeometry": True,
                    "autoEstimateCoreDiameter": True,
                    "autoInferLayout": True,
                    "showAdvancedDialog": False,
                    "useExistingGridUnlessRectangleSelected": True,
                    "trustNondefaultExistingGrid": True,
                    "exportQc": True,
                },
                "detection": {
                    "algorithmVersion": "corealign-grid-3.0-adaptive",
                    "channelMode": "nuclear",
                    "autoRetryMergedChannels": True,
                    "minAssignedFractionToBuildGrid": 0.3,
                    "minAssignedFractionForReview": 0.75,
                    "requireEveryRowAndColumn": True,
                    "maxMissingFractionToPreserve": 0.06,
                },
                "orientation": {
                    "algorithmVersion": (
                        "skin-epidermis-orient-3.7-rotated-multichannel" if skin
                        else "generic-peripheral-orient-3.7-rotated-multichannel"
                    ),
                    "analysisDownsample": 4,
                    "exportDownsample": 1,
                    "parallelWorkers": 2,
                    "cropScale": 1.05,
                    "rotationSupportScale": 1.45,
                    "regionRefinementEnabled": True,
                    "saveFullResolutionPng": True,
                    "saveNativeOmeTiff": False,
                    "saveRotatedMultichannelOmeTiff": output == "research",
                    "nuclearChannelTokens": ["dapi", "hoechst", "nuclear"],
                    "epidermisChannelTokens": ["keratin", "cytokeratin", "panck", "epcam"],
                    "rgbRedChannelTokens": ["keratin", "cytokeratin", "panck", "epcam"],
                    "rgbGreenChannelTokens": [],
                    "overrideClassName": "Epidermis override" if skin else "Orientation override",
                },
                "quality": {
                    "requireHumanGridApproval": True,
                    "requireHumanOrientationApproval": True,
                    "blockPresentationWhenAnySelectedCoreNeedsReview": True,
                },
                "presentation": {
                    "enabled": False,
                    "conditions": [],
                    "treatmentColumns": [],
                    "comparisons": [],
                },
            }
        },
    }


class Run:
    """One slide, from Start to the last exported file."""

    ENDPOINT_RE = re.compile(
        r"http://127\.0\.0\.1:(\d+)/corealign/(save|open|output|gate)\?token=([A-Za-z0-9_-]+)"
    )

    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.reset()

    def reset(self) -> None:
        with self.lock:
            self.state = "idle"
            self.slide: Path | None = None
            self.project: Path | None = None
            self.tissue = "skin"
            self.output = "presentation"
            self.process: subprocess.Popen | None = None
            self.log_path: Path | None = None
            self.started_at = 0.0
            self.finished_at = 0.0
            self.message = ""
            self.bridge_base = ""      # http://127.0.0.1:PORT, learned from REPORT.html
            self.bridge_tokens: dict[str, str] = {}

    # -- lifecycle ----------------------------------------------------------
    def start(self, slide: Path, tissue: str, output: str) -> None:
        with self.lock:
            if self.state in ("running", "starting"):
                raise RuntimeError("a run is already in progress")
            project = slide.parent
            config = project / "corealign.config.json"
            config.write_text(json.dumps(build_config(tissue, output), indent=2) + "\n", "utf-8")

            work = project / "work"
            work.mkdir(exist_ok=True)
            self.log_path = work / "studio-run.log"
            self.log_path.write_text("", "utf-8")

            command = [
                QUPATH,
                "-D", "corealign.headless=true",
                "script", "--image", str(slide), WORKFLOW,
            ]
            handle = self.log_path.open("ab", buffering=0)
            self.process = subprocess.Popen(
                command, stdout=handle, stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL, cwd=str(project),
                start_new_session=True,
            )
            self.slide = slide
            self.project = project
            self.tissue = tissue
            self.output = output
            self.state = "running"
            self.started_at = time.time()
            self.finished_at = 0.0
            self.message = ""
            self.bridge_base = ""
            self.bridge_tokens = {}

    def stop(self) -> None:
        with self.lock:
            process = self.process
        if process and process.poll() is None:
            try:
                os.killpg(os.getpgid(process.pid), 15)
            except (ProcessLookupError, PermissionError):
                process.terminate()
        with self.lock:
            self.state = "cancelled"
            self.message = "Stopped from the Studio page."
            self.finished_at = time.time()

    def poll(self) -> None:
        """Refresh derived state. Cheap enough to call on every status request."""
        with self.lock:
            process = self.process
            if process is None or self.state in ("idle", "cancelled"):
                return
            code = process.poll()
            if code is None:
                self.state = "running"
                return
            if self.state not in ("complete", "failed"):
                self.finished_at = time.time()
                tail = self.log_tail(4000)
                if "=== CoreAlign COMPLETE" in tail:
                    self.state, self.message = "complete", "Finished and human approved."
                elif code == 0:
                    self.state = "stopped"
                    self.message = "CoreAlign stopped safely. The log says why."
                else:
                    self.state = "failed"
                    self.message = f"QuPath exited with code {code}."

    # -- files --------------------------------------------------------------
    def log_tail(self, limit: int = 20000) -> str:
        path = self.log_path
        if not path or not path.is_file():
            return ""
        try:
            data = path.read_bytes()
        except OSError:
            return ""
        return data[-limit:].decode("utf-8", "replace")

    def gate(self) -> dict | None:
        """The gate CoreAlign is currently waiting on, if any."""
        if not self.project:
            return None
        for candidate in sorted((self.project / "work" / "state").glob("*/gate.json")):
            try:
                return json.loads(candidate.read_text("utf-8"))
            except (OSError, ValueError):
                continue
        return None

    def report_html(self) -> str | None:
        """REPORT.html with the loopback URLs swapped for Studio's proxy paths.

        The token is kept here and never sent to the browser.
        """
        if not self.project:
            return None
        report = self.project / "REPORT.html"
        if not report.is_file():
            return None
        text = report.read_text("utf-8", "replace")
        found: dict[str, str] = {}
        base = ""

        def swap(match: re.Match) -> str:
            nonlocal base
            base = f"http://127.0.0.1:{match.group(1)}"
            found[match.group(2)] = match.group(3)
            return f"/api/bridge/{match.group(2)}"

        text = self.ENDPOINT_RE.sub(swap, text)
        with self.lock:
            if base:
                self.bridge_base = base
                self.bridge_tokens.update(found)
        return text

    def bridge_url(self, action: str) -> str | None:
        with self.lock:
            base, token = self.bridge_base, self.bridge_tokens.get(action)
        if base and token:
            return f"{base}/corealign/{action}?token={token}"
        # A gate can open before the browser has loaded the refreshed report, so fall back to
        # the endpoint CoreAlign itself wrote into gate.json.
        if action == "gate":
            gate = self.gate() or {}
            endpoint = str(gate.get("endpoint") or "")
            if endpoint.startswith("http://127.0.0.1:"):
                return endpoint
        return None

    # -- results ------------------------------------------------------------
    RESULT_GROUPS = (
        ("png", "results/png", "Aligned images", "One full-resolution PNG per core."),
        ("ome-tiff", "results/ome-tiff", "Research OME-TIFF", "Multichannel, original bit depth."),
        ("tables", "results/tables", "Tables and audit", "CSV and JSON: angles, QC, display ranges."),
        ("grid-qc", "qc/01-grid", "Grid QC", "The whole-slide detection image and coordinates."),
        ("core-qc", "qc/02-orientation", "Per-core QC", "Previews and the contact sheet."),
        ("qupath", "qupath", "QuPath project", "Ordered core project, research runs only."),
    )

    def results(self) -> list[dict]:
        """What this run has actually produced, folder by folder."""
        if not self.project:
            return []
        groups = []
        for key, relative, title, note in self.RESULT_GROUPS:
            folder = self.project / relative
            if not folder.is_dir():
                continue
            files, total = [], 0
            for item in sorted(folder.rglob("*")):
                if not item.is_file():
                    continue
                try:
                    size = item.stat().st_size
                except OSError:
                    continue
                total += size
                files.append({
                    "name": str(item.relative_to(folder)),
                    "path": str(item.relative_to(self.project)),
                    "size": human_size(size),
                })
            if not files:
                continue
            groups.append({
                "key": key, "folder": relative, "title": title, "note": note,
                "count": len(files), "size": human_size(total),
                "files": files[:400], "truncated": len(files) > 400,
            })
        return groups

    def group_folder(self, key: str) -> Path | None:
        if not self.project:
            return None
        for candidate, relative, _title, _note in self.RESULT_GROUPS:
            if candidate == key:
                folder = self.project / relative
                return folder if folder.is_dir() else None
        return None

    def snapshot(self) -> dict:
        self.poll()
        gate = self.gate()
        with self.lock:
            elapsed = int((self.finished_at or time.time()) - self.started_at) if self.started_at else 0
            return {
                "state": self.state,
                "message": self.message,
                "slide": str(self.slide) if self.slide else "",
                "project": str(self.project) if self.project else "",
                "tissue": self.tissue,
                "output": self.output,
                "elapsedSeconds": elapsed,
                "gate": gate,
                "hasReport": bool(self.project and (self.project / "REPORT.html").is_file()),
                "results": self.results(),
            }


RUN = Run()


class ChunkedWriter:
    """Minimal file-like object that writes HTTP chunked-encoding frames.

    zipfile needs something it can write to and tell(); it never seeks backwards when
    allowZip64 is on and the stream is not seekable, so tracking the offset is enough.
    """

    def __init__(self, raw) -> None:
        self.raw = raw
        self.offset = 0

    def write(self, data: bytes) -> int:
        if not data:
            return 0
        self.raw.write(b"%X\r\n" % len(data))
        self.raw.write(data)
        self.raw.write(b"\r\n")
        self.offset += len(data)
        return len(data)

    def tell(self) -> int:
        return self.offset

    def flush(self) -> None:
        try:
            self.raw.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def close(self) -> None:
        self.raw.write(b"0\r\n\r\n")
        self.flush()


class Handler(BaseHTTPRequestHandler):
    server_version = "CoreAlignStudio/1.0"
    protocol_version = "HTTP/1.1"

    # -- helpers ------------------------------------------------------------
    def _send(self, status: int, body: bytes, content_type: str, extra: dict | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def json_out(self, payload: dict, status: int = 200) -> None:
        self._send(status, json.dumps(payload).encode("utf-8"), "application/json; charset=utf-8")

    def text_out(self, text: str, status: int = 200, content_type: str = "text/plain; charset=utf-8") -> None:
        self._send(status, text.encode("utf-8"), content_type)

    def read_json(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return {}
        if length <= 0 or length > MAX_BODY:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, OSError):
            return {}

    def log_message(self, fmt: str, *args) -> None:  # quieter than the default
        sys.stderr.write("studio %s\n" % (fmt % args))

    # -- routes -------------------------------------------------------------
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        route = unquote(parsed.path)
        query = parse_qs(parsed.query)
        try:
            if route in ("/", "/index.html"):
                return self.serve_static("index.html")
            if route == "/healthz":
                return self.text_out("ok")
            if route == "/api/roots":
                return self.json_out({"roots": [
                    {"path": str(root), "name": root.name or str(root),
                     "writable": os.access(root, os.W_OK)} for root in ROOTS]})
            if route == "/api/browse":
                return self.browse(query.get("path", [""])[0])
            if route == "/api/status":
                return self.json_out(RUN.snapshot())
            if route == "/api/log":
                return self.json_out({"log": RUN.log_tail()})
            if route == "/api/results":
                return self.json_out({"results": RUN.results()})
            if route == "/api/download":
                return self.download_file(query.get("path", [""])[0])
            if route == "/api/download-zip":
                return self.download_zip(query.get("group", ["all"])[0])
            if route.startswith("/project/"):
                return self.serve_project(route[len("/project/"):])
            if route.startswith("/static/"):
                return self.serve_static(route[len("/static/"):])
            return self.json_out({"error": "not found"}, 404)
        except BrokenPipeError:
            pass
        except Exception as error:  # never take the server down for one bad request
            self.log_message("GET %s failed: %s", route, error)
            self.json_out({"error": str(error)}, 500)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        route = unquote(parsed.path)
        try:
            if route == "/api/run":
                return self.start_run()
            if route == "/api/stop":
                RUN.stop()
                return self.json_out(RUN.snapshot())
            if route == "/api/reset":
                RUN.reset()
                return self.json_out(RUN.snapshot())
            if route.startswith("/api/bridge/"):
                return self.proxy_bridge(route[len("/api/bridge/"):])
            return self.json_out({"error": "not found"}, 404)
        except BrokenPipeError:
            pass
        except Exception as error:
            self.log_message("POST %s failed: %s", route, error)
            self.json_out({"error": str(error)}, 500)

    # -- handlers -----------------------------------------------------------
    def browse(self, raw: str) -> None:
        target = Path(raw) if raw else (ROOTS[0] if ROOTS else Path.home())
        if not inside_roots(target):
            return self.json_out({"error": "that folder is outside the allowed roots"}, 403)
        target = target.resolve()
        if not target.is_dir():
            return self.json_out({"error": "not a folder"}, 404)
        folders, slides = [], []
        try:
            for entry in sorted(target.iterdir(), key=lambda item: item.name.lower()):
                if entry.name.startswith("."):
                    continue
                try:
                    if entry.is_dir():
                        if entry.name in HIDDEN_DIRS:
                            continue
                        folders.append({"name": entry.name, "path": str(entry)})
                    elif is_slide(entry):
                        size = entry.stat().st_size
                        # inside_roots resolves symlinks, so a link pointing at an unbound
                        # share fails on Start. Say that here rather than in an error later.
                        reachable = inside_roots(entry)
                        slides.append({
                            "name": entry.name, "path": str(entry),
                            "size": human_size(size),
                            "folderWritable": os.access(entry.parent, os.W_OK),
                            "reachable": reachable,
                            "linkTarget": (str(entry.resolve()) if entry.is_symlink() else ""),
                        })
                except OSError:
                    continue
        except PermissionError:
            return self.json_out({"error": "no permission to read that folder"}, 403)
        parent = str(target.parent) if inside_roots(target.parent) and target.parent != target else ""
        return self.json_out({
            "path": str(target), "parent": parent,
            "writable": os.access(target, os.W_OK),
            "folders": folders, "slides": slides,
        })

    def start_run(self) -> None:
        payload = self.read_json()
        slide = Path(str(payload.get("slide") or ""))
        tissue = "skin" if payload.get("tissue") != "other" else "other"
        output = "research" if payload.get("output") == "research" else "presentation"
        if not slide.name or not inside_roots(slide):
            target = ""
            try:
                if slide.is_symlink():
                    target = f" It is a link to {slide.resolve()}, which is not one of them."
            except OSError:
                pass
            return self.json_out({"error":
                "That slide is outside the folders this app is allowed to open." + target}, 403)
        if not slide.is_file():
            return self.json_out({"error": "no such slide"}, 404)
        if not os.access(slide.parent, os.W_OK):
            return self.json_out({"error":
                "CoreAlign writes its results beside the slide, and that folder is read only. "
                "Copy the slide into your locker, or ask for write access to this share."}, 400)
        if not Path(QUPATH).exists():
            return self.json_out({"error": f"QuPath was not found at {QUPATH}"}, 500)
        if not Path(WORKFLOW).is_file():
            return self.json_out({"error": f"CoreAlign.groovy was not found at {WORKFLOW}"}, 500)
        try:
            RUN.start(slide, tissue, output)
        except RuntimeError as error:
            return self.json_out({"error": str(error)}, 409)
        return self.json_out(RUN.snapshot())

    def proxy_bridge(self, action: str) -> None:
        """Forward one control to CoreAlign's loopback bridge.

        The browser never sees the bridge address or its token; it only ever names an action.
        """
        if action not in ("save", "open", "output", "gate"):
            return self.json_out({"error": "unknown action"}, 404)
        url = RUN.bridge_url(action)
        if not url:
            return self.json_out({"ok": False,
                "error": "CoreAlign is not listening for that right now"}, 409)
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        body = self.rfile.read(length) if 0 < length <= MAX_BODY else b"{}"
        request = urllib.request.Request(url, data=body, method="POST",
                                         headers={"Content-Type": "text/plain;charset=UTF-8"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                payload = response.read()
                return self._send(response.status, payload, "application/json; charset=utf-8")
        except urllib.error.HTTPError as error:
            return self._send(error.code, error.read() or b"{}", "application/json; charset=utf-8")
        except (urllib.error.URLError, socket.timeout, OSError) as error:
            return self.json_out({"ok": False, "error": f"could not reach CoreAlign: {error}"}, 502)

    def resolve_in_project(self, relative: str) -> Path | None:
        project = RUN.project
        if not project or not relative:
            return None
        try:
            resolved = (project / relative).resolve()
            resolved.relative_to(project.resolve())
        except (OSError, ValueError):
            return None
        return resolved

    def download_file(self, relative: str) -> None:
        target = self.resolve_in_project(relative)
        if target is None or not target.is_file():
            return self.json_out({"error": "not found"}, 404)
        kind = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        try:
            size = target.stat().st_size
        except OSError as error:
            return self.json_out({"error": str(error)}, 500)
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", f'attachment; filename="{target.name}"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            with target.open("rb") as handle:
                shutil.copyfileobj(handle, self.wfile, 1024 * 256)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def download_zip(self, group: str) -> None:
        """Stream a zip of one result folder, or of everything worth keeping.

        Streamed rather than built in a temp file: a research run's PNG folder can be larger than
        anything this job should be writing twice.
        """
        project = RUN.project
        if not project:
            return self.json_out({"error": "no run yet"}, 404)
        if group == "all":
            folders = [(key, RUN.group_folder(key)) for key, *_ in RUN.RESULT_GROUPS]
            folders = [(key, path) for key, path in folders if path is not None]
        else:
            folder = RUN.group_folder(group)
            if folder is None:
                return self.json_out({"error": "no such result folder"}, 404)
            folders = [(group, folder)]
        if not folders:
            return self.json_out({"error": "nothing to download yet"}, 404)

        stem = (RUN.slide.name.split(".")[0] if RUN.slide else "corealign")
        name = f"{stem}-{group}.zip"
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Disposition", f'attachment; filename="{name}"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        if self.command == "HEAD":
            return
        writer = ChunkedWriter(self.wfile)
        try:
            # ZIP_STORED: PNG and OME-TIFF are already compressed, so deflating them costs CPU
            # on a shared node and saves almost nothing.
            with zipfile.ZipFile(writer, "w", zipfile.ZIP_STORED, allowZip64=True) as archive:
                for key, folder in folders:
                    for item in sorted(folder.rglob("*")):
                        if not item.is_file():
                            continue
                        try:
                            archive.write(item, f"{key}/{item.relative_to(folder)}")
                        except OSError:
                            continue
            writer.close()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def serve_project(self, relative: str) -> None:
        project = RUN.project
        if not project:
            return self.json_out({"error": "no run yet"}, 404)
        if relative in ("", "REPORT.html"):
            text = RUN.report_html()
            if text is None:
                return self.text_out("<p>The report is not written yet.</p>", 404, "text/html; charset=utf-8")
            return self.text_out(text, 200, "text/html; charset=utf-8")
        target = (project / relative)
        try:
            resolved = target.resolve()
            resolved.relative_to(project.resolve())
        except (OSError, ValueError):
            return self.json_out({"error": "outside the project"}, 403)
        if not resolved.is_file():
            return self.json_out({"error": "not found"}, 404)
        kind = mimetypes.guess_type(resolved.name)[0] or "application/octet-stream"
        try:
            return self._send(200, resolved.read_bytes(), kind)
        except OSError as error:
            return self.json_out({"error": str(error)}, 500)

    def serve_static(self, relative: str) -> None:
        target = (STATIC / relative)
        try:
            resolved = target.resolve()
            resolved.relative_to(STATIC.resolve())
        except (OSError, ValueError):
            return self.json_out({"error": "forbidden"}, 403)
        if not resolved.is_file():
            return self.json_out({"error": "not found"}, 404)
        kind = mimetypes.guess_type(resolved.name)[0] or "application/octet-stream"
        return self._send(200, resolved.read_bytes(), kind)


def main() -> int:
    if not STATIC.is_dir():
        print(f"missing static directory: {STATIC}", file=sys.stderr)
        return 2
    print(f"CoreAlign Studio on {BIND}:{PORT}")
    print(f"  QuPath   {QUPATH} {'(found)' if Path(QUPATH).exists() else '(MISSING)'}")
    print(f"  workflow {WORKFLOW} {'(found)' if Path(WORKFLOW).is_file() else '(MISSING)'}")
    print(f"  roots    {', '.join(str(root) for root in ROOTS) or '(none)'}")
    server = ThreadingHTTPServer((BIND, PORT), Handler)
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopping")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
