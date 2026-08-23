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

The review screen is Studio's own
--------------------------------
It is drawn from /api/review, which reads the run's own JSON: qc/02-orientation/run_report.json
for the cores and qc/01-grid for the detected grid. Embedding REPORT.html in a frame was tried
first and was wrong: the report carries its own sticky header and gate bar, which stacked on top
of Studio's and made the page hard to read and hard to click. REPORT.html is still written by
CoreAlign and still served at /project/REPORT.html with its loopback URLs rewritten, so it stays
a working artefact to keep or open elsewhere. The page just no longer depends on it.

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


def allocated_cpus() -> int:
    """How many CPUs this Slurm job actually holds.

    Slurm exports the allocation; os.cpu_count() reports the whole node, which on a
    112-core box would be a request to oversubscribe by 14x. Prefer what was allocated,
    and only fall back to the machine when nothing was.
    """
    for name in ("SLURM_CPUS_PER_TASK", "APPHUB_CPUS", "SLURM_CPUS_ON_NODE"):
        try:
            value = int(os.environ.get(name, "") or 0)
        except ValueError:
            continue
        if value > 0:
            return value
    return os.cpu_count() or 1


def allocated_memory_mb() -> int:
    """How much memory this Slurm job holds, in MB. 0 when nothing says."""
    for name in ("SLURM_MEM_PER_NODE", "APPHUB_MEMORY_MB", "SLURM_MEM_PER_CPU"):
        raw = os.environ.get(name, "") or ""
        try:
            value = int(raw)
        except ValueError:
            continue
        if value <= 0:
            continue
        if name == "SLURM_MEM_PER_CPU":
            value *= allocated_cpus()
        return value
    return 0


# Measured on node3, 23 Aug 2026, on the 117-core reference slide, steady state:
#
#     2 workers   21.0 s per core    40.9 min
#     8 workers    6.0 s per core    11.7 min
#    16 workers    6.1 s per core    11.9 min
#
# It plateaus at 8. Past that the extra workers wait on something shared, most likely the
# read path to the NAS, and only add memory pressure: each worker holds its own
# full-resolution crop on top of QuPath's shared tile cache. A 16-worker run with an
# uncapped JVM heap was killed by Slurm at 94 GB of a 96 GB allocation.
WORKER_MEMORY_MB = 2048
JVM_HEADROOM_MB = 8192
# The measurement above, not a guess. A different slide on a faster path might scale
# further; a config can still ask for more explicitly, up to the runner's own limit of 32.
MAX_WORKERS = 8


def orientation_workers() -> int:
    """Cores are processed independently, so use the allocation, bounded by its memory.

    One CPU is left over so this server keeps answering the page while QuPath saturates
    the rest. Below 4 CPUs there is nothing to spare, so use them all.
    """
    cpus = allocated_cpus()
    by_cpu = max(1, cpus - 1) if cpus >= 4 else max(1, cpus)
    memory = allocated_memory_mb()
    if memory <= 0:
        # Nothing said how much memory this job holds, so trust the CPU count only as far
        # as the measurement goes. On a 112-CPU node the bare count would ask for 111.
        return max(1, min(by_cpu, MAX_WORKERS))
    by_memory = max(1, (memory - JVM_HEADROOM_MB) // WORKER_MEMORY_MB)
    return max(1, min(by_cpu, by_memory, MAX_WORKERS))


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
                    "parallelWorkers": orientation_workers(),
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
            self._results_cache: tuple[float, list[dict]] | None = None
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
            self._results_cache = None
            self.tissue = tissue
            self.output = output
            self.state = "running"
            self.started_at = time.time()
            self.finished_at = 0.0
            self.message = ""
            self.bridge_base = ""
            self.bridge_tokens = {}

    def attach(self, slide: Path) -> bool:
        """Point at a slide's project folder without running anything.

        Picking a slide that has been through CoreAlign before should show what is already
        there. Without this the only way to see a previous result is to run it again.
        """
        with self.lock:
            if self.state in ("running", "starting"):
                return False
            self.slide = slide
            self.project = slide.parent
            self._results_cache = None
            log = slide.parent / "work" / "studio-run.log"
            self.log_path = log if log.is_file() else None
            self.bridge_base = ""
            self.bridge_tokens = {}
            return True

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

    def stale_gate(self) -> dict | None:
        """A gate.json left behind by a run that is no longer alive.

        CoreAlign deletes this file in a finally block, which never runs when the JVM is
        killed: a cancelled job, an out-of-memory kill, a node going away. The file then
        sits in the project describing a question nobody is waiting for, and the loopback
        port it names belongs to a process that is gone. Answering it fails with a bare
        "Connection refused", which reads like a network fault and is not one.
        """
        if not self.project:
            return None
        for candidate in sorted((self.project / "work" / "state").glob("*/gate.json")):
            try:
                return json.loads(candidate.read_text("utf-8"))
            except (OSError, ValueError):
                continue
        return None

    def gate(self) -> dict | None:
        """The gate CoreAlign is currently waiting on, if any.

        Only this Studio's own QuPath can be waiting: it is a child of this process. If it
        is not running, whatever is on disk is a leftover, not a question.
        """
        with self.lock:
            process = self.process
            alive = process is not None and process.poll() is None
        if not alive:
            return None
        return self.stale_gate()

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

    def discover_bridge(self) -> None:
        """Learn the loopback endpoints by reading REPORT.html here on the node.

        The page used to be the one that taught us these, as a side effect of being
        served the report. It no longer loads the report at all, so read the file
        directly. The token stays in this process either way.
        """
        if not self.project:
            return
        report = self.project / "REPORT.html"
        if not report.is_file():
            return
        try:
            text = report.read_text("utf-8", "replace")
        except OSError:
            return
        base, found = "", {}
        for match in self.ENDPOINT_RE.finditer(text):
            base = f"http://127.0.0.1:{match.group(1)}"
            found[match.group(2)] = match.group(3)
        if not base:
            return
        with self.lock:
            self.bridge_base = base
            self.bridge_tokens.update(found)

    def bridge_url(self, action: str) -> str | None:
        with self.lock:
            base, token = self.bridge_base, self.bridge_tokens.get(action)
        if not (base and token):
            self.discover_bridge()
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
    # Titles are what the page shows, and the people using Studio read Thai.
    RESULT_GROUPS = (
        ("png", "results/png", "ภาพที่จัดเรียงแล้ว",
         "PNG ความละเอียดเต็ม หนึ่งไฟล์ต่อหนึ่ง core"),
        ("ome-tiff", "results/ome-tiff", "OME-TIFF สำหรับวิเคราะห์ต่อ",
         "หลายแชนเนล บิตเดปธ์เดิม"),
        ("tables", "results/tables", "ตารางและบันทึกตรวจสอบ",
         "CSV และ JSON: มุมหมุน ค่า QC และช่วงการแสดงผล"),
        ("grid-qc", "qc/01-grid", "QC ของกริด",
         "ภาพตรวจจับทั้งสไลด์ พร้อมพิกัดของทุกตำแหน่ง"),
        ("core-qc", "qc/02-orientation", "QC ราย core",
         "ภาพตัวอย่างก่อนและหลังหมุน พร้อม contact sheet"),
        ("qupath", "qupath", "โปรเจกต์ QuPath",
         "โปรเจกต์ core ที่เรียงลำดับแล้ว เฉพาะโหมดวิเคราะห์ต่อ"),
    )

    # The page polls status every two seconds and this walk stats every produced file, which
    # for a finished run is several hundred on an NFS share. Hold the answer briefly: a run
    # that is writing files still shows them within a few seconds, and an idle tab left open
    # stops hammering the lab NAS.
    RESULTS_TTL_SECONDS = 5.0

    def results(self, detail: bool = True) -> list[dict]:
        """What this run has actually produced, folder by folder."""
        if not self.project:
            return []
        with self.lock:
            cached = self._results_cache
        if cached and time.time() - cached[0] < self.RESULTS_TTL_SECONDS:
            groups = cached[1]
            if detail:
                return groups
            return [{k: v for k, v in group.items() if k != "files"} for group in groups]
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
        with self.lock:
            self._results_cache = (time.time(), groups)
        if detail:
            return groups
        return [{k: v for k, v in group.items() if k != "files"} for group in groups]

    def group_folder(self, key: str) -> Path | None:
        if not self.project:
            return None
        for candidate, relative, _title, _note in self.RESULT_GROUPS:
            if candidate == key:
                folder = self.project / relative
                return folder if folder.is_dir() else None
        return None

    # -- review -------------------------------------------------------------
    @staticmethod
    def _flatten(value):
        """CoreAlign writes some QC warnings as Groovy GStrings, which land in JSON as
        {"strings": [...], "values": [...]} instead of a sentence. Put them back together
        rather than showing a person the raw object."""
        if isinstance(value, dict) and "strings" in value:
            parts, strings = [], value.get("strings") or []
            values = value.get("values") or []
            for index, chunk in enumerate(strings):
                parts.append(str(chunk))
                if index < len(values):
                    parts.append(str(values[index]))
            return "".join(parts).strip()
        return str(value)

    def _read_json(self, relative: str):
        if not self.project:
            return None
        target = self.project / relative
        if not target.is_file():
            return None
        try:
            return json.loads(target.read_text("utf-8"))
        except (OSError, ValueError):
            return None

    def review(self) -> dict:
        """Everything the review screen draws, taken from the run's own JSON.

        The screen is built from this, not from REPORT.html. One source of truth for the
        numbers, and the page can lay them out however reads best.
        """
        if not self.project:
            return {"available": False}
        grid_json, grid_image = None, ""
        grid_dir = self.project / "qc" / "01-grid"
        if grid_dir.is_dir():
            for candidate in sorted(grid_dir.glob("*_grid_qc_latest.json")):
                try:
                    grid_json = json.loads(candidate.read_text("utf-8"))
                except (OSError, ValueError):
                    grid_json = None
                break
            for candidate in sorted(grid_dir.glob("*_grid_qc_latest.png")):
                grid_image = f"qc/01-grid/{candidate.name}"
                break

        grid = {}
        if grid_json:
            grid = {
                "image": grid_image,
                "rows": grid_json.get("gridHeight"),
                "cols": grid_json.get("gridWidth"),
                "coreCount": grid_json.get("coreCount"),
                "present": grid_json.get("present"),
                "missing": grid_json.get("missing"),
                "reviewQueueCount": grid_json.get("reviewQueueCount"),
                "humanCorrectedTotal": grid_json.get("humanCorrectedTotal"),
                "warnings": [self._flatten(item) for item in (grid_json.get("warnings") or [])],
                "hardErrors": [self._flatten(item) for item in (grid_json.get("hardErrors") or [])],
            }

        report = self._read_json("qc/02-orientation/run_report.json")
        if not report:
            return {"available": False, "grid": grid,
                    "contactSheet": "", "cores": [], "corrections": {}}

        base_run = ""
        run_directory = str((report.get("outputs") or {}).get("runDirectory") or "")
        if run_directory:
            base_run = Path(run_directory).name

        saved = self._read_json("corealign-review-corrections.json") or {}
        corrections = {}
        if str(saved.get("baseRun") or "") == base_run:
            for item in saved.get("corrections") or []:
                name = str(item.get("core") or "")
                try:
                    corrections[name] = float(item.get("rotationAdjustmentDeg") or 0.0)
                except (TypeError, ValueError):
                    continue

        contact = self.project / "qc" / "02-orientation" / "orientation_contact_sheet.png"
        cores = []
        for entry in report.get("cores") or []:
            name = str(entry.get("core") or "")
            cores.append({
                "index": entry.get("index"),
                "core": name,
                "row": entry.get("row"),
                "col": entry.get("col"),
                "status": entry.get("status"),
                "regionStatus": entry.get("regionStatus"),
                "confidence": entry.get("confidence"),
                "rotateToTopDeg": entry.get("rotateToTopDeg"),
                "adjustmentDeg": entry.get("webRotationAdjustmentDeg") or 0.0,
                "residualDeg": entry.get("postRotationResidualDeg"),
                "reasons": [self._flatten(item) for item in (entry.get("reasons") or [])],
                "rotated": entry.get("rotatedPreview") or "",
                "unrotated": entry.get("unrotatedPreview") or "",
            })

        return {
            "available": True,
            "image": report.get("image") or "",
            "baseRun": base_run,
            "status": report.get("status") or "",
            "message": report.get("message") or "",
            "counts": report.get("counts") or {},
            "qc": report.get("qc") or {},
            "elapsed": report.get("elapsed") or "",
            "grid": grid,
            "contactSheet": ("qc/02-orientation/orientation_contact_sheet.png"
                             if contact.is_file() else ""),
            "cores": cores,
            "corrections": corrections,
        }

    def save_corrections(self, edits: list) -> dict:
        """Send angle changes to CoreAlign's bridge.

        The page sends core names and angles only. Which run they belong to is decided
        here from the report, so a stale tab cannot write corrections onto a different run.
        """
        model = self.review()
        if not model.get("available"):
            return {"ok": False, "error": "There is nothing to review yet."}
        allowed = {str(core["core"]).lower() for core in model["cores"]}
        clean = []
        seen = set()
        for item in edits or []:
            name = str((item or {}).get("core") or "").strip()
            key = name.lower()
            try:
                angle = float((item or {}).get("rotationAdjustmentDeg"))
            except (TypeError, ValueError):
                return {"ok": False, "error": f"{name or 'A core'} has no usable angle."}
            if not name or key not in allowed:
                return {"ok": False, "error": f"{name or 'A core'} is not a core in this run."}
            if key in seen:
                return {"ok": False, "error": f"{name} was sent twice."}
            if not -180.0 <= angle <= 180.0:
                return {"ok": False,
                        "error": f"{name}: an angle has to be between -180 and 180, not {angle:g}."}
            seen.add(key)
            clean.append({"core": name, "rotationAdjustmentDeg": round(angle, 1)})

        document = {
            "schemaVersion": 1,
            "image": model["image"],
            "baseRun": model["baseRun"],
            "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "corrections": clean,
        }
        payload = json.dumps(document).encode("utf-8")

        url = self.bridge_url("save")
        if not url:
            # No bridge means no run is waiting, which is the normal state when someone
            # opens a finished project to look at it. The bridge only ever writes this one
            # file, and CoreAlign reads it at the gate, so writing it here is the same act.
            target = self.project / "corealign-review-corrections.json"
            temporary = target.with_name("." + target.name + ".tmp")
            try:
                temporary.write_text(json.dumps(document, indent=2) + "\n", "utf-8")
                os.replace(temporary, target)
            except OSError as error:
                return {"ok": False, "error": f"Could not write the corrections file: {error}"}
            return {"ok": True, "saved": len(clean), "via": "file"}

        request = urllib.request.Request(
            url, data=payload, method="POST",
            headers={"Content-Type": "text/plain;charset=UTF-8"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            return {"ok": False, "error": error.read().decode("utf-8", "replace")[:400]}
        except OSError as error:
            return {"ok": False, "error": str(error)}
        try:
            answer = json.loads(body)
        except ValueError:
            answer = {"ok": True}
        answer["saved"] = len(clean)
        answer["via"] = "bridge"
        return answer

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
                # A question from a run that has since died. Not answerable, but worth
                # showing: it says how far the previous attempt got.
                "abandonedGate": (self.stale_gate() if gate is None else None),
                "hasReport": bool(self.project and (self.project / "REPORT.html").is_file()),
                # What the page actually draws from. REPORT.html is only an artefact now,
                # and a project can hold a finished run without one.
                "hasResults": bool(self.project and
                                   (self.project / "qc" / "02-orientation" /
                                    "run_report.json").is_file()),
                "results": self.results(detail=False),
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
            if route == "/api/review":
                return self.json_out(RUN.review())
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
            if route == "/api/attach":
                raw = str(self.read_json().get("slide") or "")
                target = Path(raw)
                # Keep the path exactly as it was picked. Resolving it would follow the
                # symlink into the share the microscope wrote to, and the project is the
                # folder the slide was picked in, not wherever the pixels live.
                if not raw or not inside_roots(target) or not target.is_file():
                    return self.json_out({"error": "that slide is not one this app can open"}, 400)
                if not RUN.attach(target):
                    return self.json_out({"error": "a run is already in progress"}, 409)
                return self.json_out(RUN.snapshot())
            if route == "/api/corrections":
                body = self.read_json()
                answer = RUN.save_corrections(body.get("corrections") or [])
                return self.json_out(answer, 200 if answer.get("ok") else 409)
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
