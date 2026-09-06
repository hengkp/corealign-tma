#!/usr/bin/env python3
"""CoreAlign Studio: run a TMA slide from a web page, with no desktop and no QuPath GUI.

The person opens one page, picks a slide, answers three questions, and presses Start. Everything
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
import math
import mimetypes
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
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


def tiff_description(path: Path | str, limit: int = 64 * 1024 * 1024) -> str:
    """Read the first TIFF ImageDescription without loading the slide pixels."""
    try:
        with open(path, "rb") as fh:
            head = fh.read(8)
            if len(head) < 8:
                return ""
            order = head[:2]
            endian = "<" if order == b"II" else ">" if order == b"MM" else None
            if endian is None:
                return ""
            magic = struct.unpack(endian + "H", head[2:4])[0]
            big = magic == 43
            if big:
                fh.seek(8)
                offset = struct.unpack(endian + "Q", fh.read(8))[0]
                fh.seek(offset)
                count = struct.unpack(endian + "Q", fh.read(8))[0]
                entry, tagfmt = 20, endian + "HHQQ"
            else:
                offset = struct.unpack(endian + "I", head[4:8])[0]
                fh.seek(offset)
                count = struct.unpack(endian + "H", fh.read(2))[0]
                entry, tagfmt = 12, endian + "HHII"
            for _ in range(count):
                raw = fh.read(entry)
                tag, typ, n, value = struct.unpack(tagfmt, raw[:struct.calcsize(tagfmt)])
                if tag != 270:
                    continue
                if n > limit:
                    return ""
                inline = 4 if not big else 8
                if n <= inline:
                    start = struct.calcsize(tagfmt) - inline
                    return raw[start:][:n].decode("utf-8", "replace")
                fh.seek(value)
                return fh.read(n).decode("utf-8", "replace")
    except Exception:
        # Channel metadata is an optional speed-up. A damaged header must not prevent the
        # operator from starting the same all-channel run that worked before this feature.
        return ""
    return ""


def channel_kind(name: str) -> str:
    lowered = name.casefold()
    if any(token in lowered for token in ("dapi", "hoechst", "nuclear")):
        return "nuclear"
    if re.fullmatch(r"af\d*", name.strip(), re.IGNORECASE):
        return "autofluorescence"
    return "marker"


def read_channels(path: Path | str) -> dict:
    """Return channel choices from TIFF metadata, or an unavailable response on any failure."""
    unavailable = {"available": False, "channels": [], "suggested": []}
    try:
        description = tiff_description(path).rstrip("\x00")
        if not description:
            return unavailable

        names: list[str] = []
        source = "ome-xml"
        try:
            root = ET.fromstring(description)
            pixels = next((element for element in root.iter()
                           if element.tag.rsplit("}", 1)[-1] == "Pixels"), None)
            elements = ([element for element in pixels
                         if element.tag.rsplit("}", 1)[-1] == "Channel"]
                        if pixels is not None else [])
            found = [str(element.attrib.get("Name") or "").strip() for element in elements]
            if found and all(found):
                names = found
        except (ET.ParseError, ValueError):
            pass

        if not names:
            source = "qptiff"
            found = []
            pattern = re.compile(
                r"<ScanColorTable-(\d+)\b[^>]*>(.*?)</ScanColorTable-\1\s*>",
                re.IGNORECASE | re.DOTALL,
            )
            for match in pattern.finditer(description):
                name = html.unescape(match.group(2)).strip()
                if name:
                    found.append((int(match.group(1)), name))
            names = [name for _, name in sorted(found, key=lambda item: item[0])]

        if not names:
            return unavailable
        channels = [
            {"index": index, "name": name, "kind": channel_kind(name)}
            for index, name in enumerate(names)
        ]
        suggested = [item["index"] for item in channels if item["kind"] == "nuclear"]
        if not suggested:
            suggested = [item["index"] for item in channels
                         if item["kind"] != "autofluorescence"]
        if not suggested:
            suggested = [item["index"] for item in channels]
        return {"available": True, "source": source,
                "channels": channels, "suggested": suggested}
    except Exception:
        # Metadata from scanners varies widely. Unknown metadata means all channels, which
        # is slower but remains correct and keeps setup usable for every existing slide.
        return unavailable


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
# Re-measured end to end on node2, 26 Aug 2026, on the 126-position reference slide, one run
# at a time so they did not contend for the same NAS read path:
#
#      8 workers, 12 CPUs,  48 GB   Step 2 took 987 s   peak RSS  38 GB
#     16 workers, 20 CPUs,  96 GB               662 s             75 GB
#     32 workers, 36 CPUs, 160 GB               566 s            ~150 GB
#
# It does not plateau at 8. The 23 Aug measurement that said so had its 16-worker arm killed
# by Slurm before it could be judged, and the conclusion was drawn anyway.
#
# Peak memory tracked ~5 GB per worker at every point, not the 2 GB this once assumed · which
# is why the guard used to let a job ask for far more workers than its allocation could hold.
WORKER_MEMORY_MB = 5120
JVM_HEADROOM_MB = 8192
MAX_WORKERS = int(os.environ.get("COREALIGN_MAX_WORKERS") or 32)


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


def build_config(tissue: str) -> dict:
    """The Studio setup choice in the shape CoreAlign expects."""
    skin = tissue == "skin"
    config = {
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
                    "saveRotatedMultichannelOmeTiff": True,
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
    return config


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
            self.process: subprocess.Popen | None = None
            self._results_cache: tuple[float, list[dict]] | None = None
            self.log_path: Path | None = None
            self.started_at = 0.0
            self.finished_at = 0.0
            self.message = ""
            self.bridge_base = ""      # http://127.0.0.1:PORT, learned from REPORT.html
            self.bridge_tokens: dict[str, str] = {}

    # -- lifecycle ----------------------------------------------------------
    def start(self, slide: Path, tissue: str) -> None:
        with self.lock:
            if self.state in ("running", "starting"):
                raise RuntimeError("a run is already in progress")
            project = slide.parent
            config = project / "corealign.config.json"
            config.write_text(
                json.dumps(build_config(tissue), indent=2) + "\n", "utf-8")

            work = project / "work"
            work.mkdir(exist_ok=True)
            self.log_path = work / "studio-run.log"
            self.log_path.write_text("", "utf-8")

            # A gate.json left by a run that was killed describes a question this run has
            # not asked yet. The liveness check alone does not catch it, because from the
            # moment QuPath starts there is a live process again. Clear it here: the run
            # about to start writes its own when it actually reaches a gate.
            for leftover in (work / "state").glob("*/gate.json"):
                try:
                    leftover.unlink()
                except OSError:
                    pass

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
            if self.project != slide.parent:
                # A different project inherits nothing. Leaving state at "complete" made the
                # next slide look finished before it had been looked at once.
                self.state = "idle"
                self.message = ""
                self.process = None
                self.started_at = 0.0
                self.finished_at = 0.0
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

    def alive(self) -> bool:
        """Whether the QuPath this Studio started is still running.

        Only this Studio's own QuPath can be waiting for anything: it is a child of this
        process. Once it is gone, everything it left in the project describes a question
        nobody is listening for, and every address it wrote down belongs to a process that
        no longer exists. One check, so a caller cannot answer that question differently
        from the rest of the file.
        """
        with self.lock:
            process = self.process
        return process is not None and process.poll() is None

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
        if not self.alive():
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
                # Replace, so a port from an earlier step cannot outlive the report it
                # came from.
                self.bridge_tokens = found
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
            # Replace, do not merge: a stale port for one action would otherwise survive
            # every rediscovery.
            self.bridge_tokens = found

    def bridge_url(self, action: str) -> str | None:
        """Where to send one control, worked out fresh every time.

        The bridge opens ServerSocket(0), so it takes a new random port every time CoreAlign
        rewrites the report, which it does at each step. Caching the port meant the first
        gate answered fine and the next one failed with a bare "Connection refused" against
        a port nothing was listening on any more. These are rare, deliberate clicks, so
        reading the current answer costs nothing worth saving.
        """
        # Both places this address is read from outlive the JVM that wrote them. REPORT.html
        # stays in the project for good, and gate.json survives any kill that skips the
        # finally block, so a finished project still names a port that nothing is listening
        # on. Handing that back is worse than returning nothing: the caller cannot tell a
        # dead bridge from a live one and posts to it, and the reviewer is shown a bare
        # "Connection refused" for a run that ended hours ago. There is no bridge without a
        # live QuPath, and saying so is what lets save_corrections write its file instead.
        if not self.alive():
            return None
        # gate.json is written by the gate that is open right now, so for a gate decision it
        # is the most current thing on disk.
        if action == "gate":
            endpoint = str((self.gate() or {}).get("endpoint") or "")
            if endpoint.startswith("http://127.0.0.1:"):
                return endpoint
        self.discover_bridge()
        with self.lock:
            base, token = self.bridge_base, self.bridge_tokens.get(action)
        if base and token:
            return f"{base}/corealign/{action}?token={token}"
        return None

    # -- results ------------------------------------------------------------
    RESULT_GROUPS = (
        ("png", "results/png"),
        ("ome-tiff", "results/ome-tiff"),
        ("tables", "results/tables"),
        ("grid-qc", "qc/01-grid"),
        ("core-qc", "qc/02-orientation"),
        ("qupath", "qupath"),
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
        for key, relative in self.RESULT_GROUPS:
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
                "key": key, "folder": relative,
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
        for candidate, relative in self.RESULT_GROUPS:
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
        # Two files describe the grid, and which one is current depends on where the run is.
        #
        #   *_grid_geometry.json  written by the runner every time the grid gate opens, so it
        #                         always matches what is on screen. Carries the circles, the
        #                         grid hash and the mapping to overlay pixels.
        #   *_grid_qc_latest.json written by step 3, so it does not exist at all on a first
        #                         run, and afterwards can be a step behind. Carries the
        #                         review queue and the QC warnings.
        #
        # Take the positions from the first and the commentary from the second.
        grid_dir = self.project / "qc" / "01-grid"
        geometry, qc_json, grid_image = None, None, ""
        if grid_dir.is_dir():
            for candidate in sorted(grid_dir.glob("*_grid_geometry.json")):
                try:
                    geometry = json.loads(candidate.read_text("utf-8"))
                except (OSError, ValueError):
                    geometry = None
                break
            for candidate in sorted(grid_dir.glob("*_grid_qc_latest.json")):
                try:
                    qc_json = json.loads(candidate.read_text("utf-8"))
                except (OSError, ValueError):
                    qc_json = None
                break
            named = str((geometry or {}).get("overlayPng") or "")
            if named and (grid_dir / named).is_file():
                grid_image = f"qc/01-grid/{named}"
            else:
                for pattern in ("*_grid_qc_latest.png", "*_grid_qc.png"):
                    found = sorted(grid_dir.glob(pattern))
                    if found:
                        grid_image = f"qc/01-grid/{found[0].name}"
                        break

        grid_json = geometry or qc_json
        grid = {}
        if grid_json:
            commentary = qc_json or {}
            grid = {
                "image": grid_image,
                "rows": grid_json.get("gridHeight"),
                "cols": grid_json.get("gridWidth"),
                "coreCount": grid_json.get("coreCount"),
                "present": grid_json.get("present"),
                "missing": grid_json.get("missing"),
                "reviewQueueCount": commentary.get("reviewQueueCount"),
                "humanCorrectedTotal": commentary.get("humanCorrectedTotal"),
                "warnings": [self._flatten(item) for item in (commentary.get("warnings") or [])],
                "hardErrors": [self._flatten(item) for item in (commentary.get("hardErrors") or [])],
                # What the grid editor needs: the circles, and the mapping from slide pixels
                # to overlay pixels so the page can place them on the QC image and send an
                # edit back in the coordinates CoreAlign works in.
                "gridHash": grid_json.get("gridHash") or "",
                "overviewWidth": grid_json.get("overviewWidth"),
                "overviewHeight": grid_json.get("overviewHeight"),
                "slideWidth": grid_json.get("slideWidth"),
                "slideHeight": grid_json.get("slideHeight"),
                "cores": [{
                    "core": item.get("core") or "",
                    "row": item.get("row"), "col": item.get("column"),
                    "centerX": item.get("centerX"), "centerY": item.get("centerY"),
                    "diameter": item.get("diameter"),
                    "missing": bool(item.get("missing")),
                    "source": item.get("detectionSource") or "",
                    "confidence": item.get("detectionConfidence") or "",
                } for item in (grid_json.get("cores") or [])],
            }
            grid["editable"] = bool(grid["gridHash"] and grid["slideWidth"]
                                    and grid["overviewWidth"] and grid["cores"])
            saved_grid = self._read_json("corealign-grid-corrections.json") or {}
            grid["savedCorrections"] = (saved_grid.get("corrections") or []
                                        if str(saved_grid.get("baseGridHash") or "")
                                        == str(grid["gridHash"]) else [])

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

    # -- arrange ------------------------------------------------------------
    def arrange(self) -> dict:
        """The tile manifest and the figure arrangement beside the attached slide."""
        if not self.project:
            return {"available": False, "reason": "project"}
        manifest_path = self.project / "qc" / "03-arrange" / "manifest.json"
        if not manifest_path.is_file():
            return {"available": False, "reason": "tiles"}
        try:
            manifest = json.loads(manifest_path.read_text("utf-8"))
        except (OSError, ValueError):
            return {"available": False, "reason": "tiles"}

        arrangement = self._read_json("corealign-arrangement.json")
        if not isinstance(arrangement, dict):
            nuclear = next((channel for channel in (manifest.get("channels") or [])
                            if channel.get("kind") == "nuclear"), None)
            arrangement = {
                "schemaVersion": 1,
                "image": manifest.get("image") or (self.slide.name if self.slide else ""),
                "slideLayout": {"columns": [], "rowBands": []},
                "figures": [{
                    "id": "figure-1",
                    "title": "Figure 1",
                    "rows": [{"label": ""}],
                    "cols": [{"label": ""}, {"label": ""}, {"label": ""}],
                    "channels": ([{"index": nuclear["index"], "visible": True}]
                                 if nuclear else []),
                    "cells": [],
                }],
            }
        answer = {
            "available": True,
            "manifest": manifest,
            "arrangement": arrangement,
            "tileBase": "qc/03-arrange/tiles",
        }
        if manifest.get("exportTileBase"):
            answer["exportTileBase"] = manifest["exportTileBase"]
        return answer

    @staticmethod
    def _arrange_string(value, item: str) -> str | None:
        if not isinstance(value, str):
            return f"{item} must be a string."
        return None

    def save_arrangement(self, arrangement: object) -> dict:
        """Validate and atomically replace the arrangement for the attached project."""
        available = self.arrange()
        if not available.get("available"):
            reason = available.get("reason")
            sentence = ("No project is attached." if reason == "project"
                        else "The core image tiles have not been cut yet.")
            return {"ok": False, "error": sentence}
        if not isinstance(arrangement, dict):
            return {"ok": False, "error": "The arrangement must be a JSON object."}

        manifest = available["manifest"]
        channel_indices = {channel.get("index") for channel in (manifest.get("channels") or [])
                           if isinstance(channel, dict)}
        manifest_cores = {str(core.get("core") or ""): core
                          for core in (manifest.get("cores") or []) if isinstance(core, dict)}
        figures = arrangement.get("figures")
        if not isinstance(figures, list):
            return {"ok": False, "error": "Figures must be a list."}

        slide_layout = arrangement.get("slideLayout") or {}
        if not isinstance(slide_layout, dict):
            return {"ok": False, "error": "The slide layout must be an object."}
        for index, column in enumerate(slide_layout.get("columns") or []):
            if not isinstance(column, dict):
                return {"ok": False, "error": f"Slide column {index + 1} must be an object."}
            error = self._arrange_string(column.get("label"), f"Slide column {index + 1} label")
            if error:
                return {"ok": False, "error": error}
        for index, band in enumerate(slide_layout.get("rowBands") or []):
            if not isinstance(band, dict):
                return {"ok": False, "error": f"Slide row band {index + 1} must be an object."}
            error = self._arrange_string(band.get("label"), f"Slide row band {index + 1} label")
            if error:
                return {"ok": False, "error": error}

        clean = json.loads(json.dumps(arrangement))
        seen_ids: set[str] = set()
        for figure_index, figure in enumerate(figures):
            number = figure_index + 1
            if not isinstance(figure, dict):
                return {"ok": False, "error": f"Figure {number} must be an object."}
            figure_id = figure.get("id")
            if not isinstance(figure_id, str) or not figure_id.strip():
                return {"ok": False, "error": f"Figure {number} has an empty id."}
            if figure_id in seen_ids:
                return {"ok": False, "error": f"Figure id {figure_id} is used more than once."}
            seen_ids.add(figure_id)
            error = self._arrange_string(figure.get("title"), f"Figure {figure_id} title")
            if error:
                return {"ok": False, "error": error}

            rows, cols = figure.get("rows"), figure.get("cols")
            if not isinstance(rows, list) or not isinstance(cols, list):
                return {"ok": False,
                        "error": f"Figure {figure_id} rows and columns must be lists."}
            for row_index, row in enumerate(rows):
                if not isinstance(row, dict):
                    return {"ok": False,
                            "error": f"Figure {figure_id} row {row_index + 1} must be an object."}
                error = self._arrange_string(
                    row.get("label"), f"Figure {figure_id} row {row_index + 1} label")
                if error:
                    return {"ok": False, "error": error}
            for col_index, column in enumerate(cols):
                if not isinstance(column, dict):
                    return {"ok": False,
                            "error": f"Figure {figure_id} column {col_index + 1} must be an object."}
                error = self._arrange_string(
                    column.get("label"), f"Figure {figure_id} column {col_index + 1} label")
                if error:
                    return {"ok": False, "error": error}

            channels = figure.get("channels") or []
            if not isinstance(channels, list):
                return {"ok": False, "error": f"Figure {figure_id} channels must be a list."}
            for channel in channels:
                if not isinstance(channel, dict):
                    return {"ok": False,
                            "error": f"Figure {figure_id} has a channel that is not an object."}
                index = channel.get("index")
                if isinstance(index, bool) or index not in channel_indices:
                    return {"ok": False,
                            "error": f"Figure {figure_id} names unknown channel {index}."}

            cells = figure.get("cells") or []
            if not isinstance(cells, list):
                return {"ok": False, "error": f"Figure {figure_id} cells must be a list."}
            for cell_index, cell in enumerate(cells):
                item = f"Figure {figure_id} cell {cell_index + 1}"
                if not isinstance(cell, dict):
                    return {"ok": False, "error": f"{item} must be an object."}
                row, col = cell.get("row"), cell.get("col")
                if (isinstance(row, bool) or not isinstance(row, int)
                        or isinstance(col, bool) or not isinstance(col, int)
                        or not 0 <= row < len(rows) or not 0 <= col < len(cols)):
                    return {"ok": False,
                            "error": f"{item} is outside its figure rows or columns."}
                core_name = cell.get("core")
                if not isinstance(core_name, str) or core_name not in manifest_cores:
                    return {"ok": False,
                            "error": f"{item} names core {core_name or 'without a name'}, which does not exist."}
                if manifest_cores[core_name].get("missing"):
                    return {"ok": False,
                            "error": f"{item} names core {core_name}, which is marked missing."}

        clean["image"] = manifest.get("image") or (self.slide.name if self.slide else "")
        clean["createdAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        target = self.project / "corealign-arrangement.json"
        temporary = target.with_name("." + target.name + ".tmp")
        try:
            temporary.write_text(json.dumps(clean, indent=2, ensure_ascii=False) + "\n", "utf-8")
            os.replace(temporary, target)
        except OSError as error:
            return {"ok": False, "error": f"Could not write the arrangement: {error}"}
        return {"ok": True, "arrangement": clean}

    GRID_ACTIONS = ("move", "restore", "mark_missing")

    def save_grid_corrections(self, edits: list) -> dict:
        """Write the grid edits made in the page, for step 3 to apply.

        Studio writes the file rather than posting through the bridge: CoreAlign only reads
        it when the grid gate is answered, and no bridge endpoint accepts grid edits. The
        grid it was made against is stamped here, so a correction can never be applied to a
        grid that has since changed.
        """
        model = self.review()
        grid = model.get("grid") or {}
        if not grid.get("cores"):
            return {"ok": False, "error": "There is no detected grid to correct yet."}
        if not grid.get("gridHash"):
            return {"ok": False,
                    "error": "This grid has no hash, so a correction could not be tied to it."}
        known = {str(core["core"]).lower(): core for core in grid["cores"] if core.get("core")}
        width = float(grid.get("slideWidth") or 0)
        height = float(grid.get("slideHeight") or 0)

        clean, seen = [], set()
        for item in edits or []:
            name = str((item or {}).get("core") or "").strip()
            key = name.lower()
            action = str((item or {}).get("action") or "").strip().lower()
            if key not in known:
                return {"ok": False, "error": f"{name or 'A core'} is not a position in this grid."}
            if action not in self.GRID_ACTIONS:
                return {"ok": False, "error": f"{name}: {action or 'that'} is not something to do."}
            if key in seen:
                return {"ok": False, "error": f"{name} was sent twice."}
            source = known[key]
            try:
                x = float(item.get("centerX", source.get("centerX")))
                y = float(item.get("centerY", source.get("centerY")))
                diameter = float(item.get("diameter", source.get("diameter")))
            except (TypeError, ValueError):
                return {"ok": False, "error": f"{name} has no usable position."}
            if (not math.isfinite(x) or not math.isfinite(y)
                    or not math.isfinite(diameter) or diameter <= 0
                    or (width and not 0 <= x <= width)
                    or (height and not 0 <= y <= height)):
                return {"ok": False, "error": f"{name} is outside the slide."}
            seen.add(key)
            clean.append({"core": name, "action": action,
                          "centerX": round(x, 3), "centerY": round(y, 3),
                          "diameter": round(diameter, 3)})

        target = self.project / "corealign-grid-corrections.json"
        document = {
            "schemaVersion": 1,
            # The slide this belongs to. grid["image"] is the overlay's path, which is not
            # the same thing and would be misleading in an audit file.
            "image": model.get("image") or str(self.slide.name if self.slide else ""),
            "baseGridHash": grid["gridHash"],
            "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "corrections": clean,
        }
        temporary = target.with_name("." + target.name + ".tmp")
        try:
            temporary.write_text(json.dumps(document, indent=2) + "\n", "utf-8")
            os.replace(temporary, target)
        except OSError as error:
            return {"ok": False, "error": f"Could not write the grid corrections: {error}"}
        return {"ok": True, "saved": len(clean)}

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
                "elapsedSeconds": elapsed,
                "gate": gate,
                # A question from a run that has since died. Not answerable, but worth
                # showing: it says how far the previous attempt got.
                "abandonedGate": (self.stale_gate() if gate is None else None),
                "hasReport": bool(self.project and (self.project / "REPORT.html").is_file()),
                # What the page actually draws from. REPORT.html is only an artefact now,
                # and a project can hold a finished run without one.
                #
                # A project sitting at the grid gate has a grid and no orientation report at
                # all, so keying this on the report alone meant the page never fetched the
                # model and the grid screen stayed empty. Either file is worth drawing.
                "hasResults": bool(self.project and (
                    (self.project / "qc" / "02-orientation" / "run_report.json").is_file()
                    or any((self.project / "qc" / "01-grid").glob("*_grid_geometry.json"))
                    or any((self.project / "qc" / "01-grid").glob("*_grid_qc_latest.json"))
                )) if self.project else False,
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
        extra = extra or {}
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", extra.get("Cache-Control", "no-store"))
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in extra.items():
            if key == "Cache-Control":
                continue
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
            if route == "/api/channels":
                return self.channel_list(query.get("slide", [""])[0])
            if route == "/api/status":
                return self.json_out(RUN.snapshot())
            if route == "/api/log":
                return self.json_out({"log": RUN.log_tail()})
            if route == "/api/results":
                return self.json_out({"results": RUN.results()})
            if route == "/api/review":
                return self.json_out(RUN.review())
            if route == "/api/arrange":
                return self.json_out(RUN.arrange())
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
            if route == "/api/project/save":
                return self.save_project()
            if route == "/api/grid-corrections":
                body = self.read_json()
                answer = RUN.save_grid_corrections(body.get("corrections") or [])
                return self.json_out(answer, 200 if answer.get("ok") else 409)
            if route == "/api/corrections":
                body = self.read_json()
                answer = RUN.save_corrections(body.get("corrections") or [])
                return self.json_out(answer, 200 if answer.get("ok") else 409)
            if route == "/api/arrange":
                body = self.read_json()
                answer = RUN.save_arrangement(body.get("arrangement"))
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
    def channel_list(self, raw: str) -> None:
        target = Path(raw)
        if not raw or not inside_roots(target):
            return self.json_out({"error": "that slide is outside the allowed roots"}, 403)
        # Only an actual slide file has channels. A directory, a fifo or a device under an
        # allowed root would otherwise be opened and could block the request thread.
        try:
            resolved = target.resolve()
            if not (resolved.is_file() and is_slide(resolved)):
                return self.json_out({"available": False, "channels": [], "suggested": []})
        except OSError:
            return self.json_out({"available": False, "channels": [], "suggested": []})
        return self.json_out(read_channels(resolved))

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
            RUN.start(slide, tissue)
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

    def save_project(self) -> None:
        """Ask the live workflow to save, or report when its project will be ready."""
        try:
            payload, status = self.project_save_result()
        except Exception as error:
            self.log_message("project save failed: %s", error)
            payload, status = ({"ok": False,
                "error": "CoreAlign could not save the project right now. Wait for the "
                         "current step to finish, then try again."}, 409)
        return self.json_out(payload, status)

    def project_save_result(self) -> tuple[dict, int]:
        project = self.resolve_in_project(".")
        if project is None or not project.is_dir():
            return {"ok": False,
                    "error": "Choose or attach a slide before saving its project."}, 409
        if not os.access(project, os.W_OK):
            return {"ok": False,
                "error": "The project folder is read only. Copy the slide into a writable "
                         "folder or ask for write access."}, 409
        if not RUN.results(detail=False):
            return {"ok": False,
                    "error": "Run CoreAlign until it has produced a grid or result before "
                             "saving the project."}, 409

        qupath_folder = self.resolve_in_project("qupath")
        if qupath_folder is None:
            return {"ok": False,
                "error": "The QuPath project folder is outside the attached project. Remove "
                         "the qupath link from the slide folder and try again."}, 409
        if qupath_folder.exists() and (not qupath_folder.is_dir()
                                       or not os.access(qupath_folder, os.W_OK)):
            return {"ok": False,
                    "error": "The QuPath project folder is read only. Ask for write access "
                             "and try again."}, 409
        has_qupath_project = qupath_folder.is_dir() and any(qupath_folder.glob("*.qpproj"))
        url = RUN.bridge_url("save")
        if not url:
            if has_qupath_project:
                return {"ok": True, "path": str(qupath_folder)}, 200
            return {"ok": False,
                    "error": "The QuPath project will be written when the current step "
                             "finishes. Resume the run first if it has stopped."}, 409

        model = RUN.review()
        corrections = []
        for core, angle in (model.get("corrections") or {}).items():
            try:
                value = float(angle)
            except (TypeError, ValueError):
                continue
            if math.isfinite(value):
                corrections.append({"core": core, "rotationAdjustmentDeg": value})
        bridge_payload = {
            "schemaVersion": 1,
            "image": model.get("image") or (RUN.slide.name if RUN.slide else ""),
            "baseRun": model.get("baseRun") or "pending",
            "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "corrections": corrections,
        }
        request = urllib.request.Request(
            url, data=json.dumps(bridge_payload).encode("utf-8"), method="POST",
            headers={"Content-Type": "text/plain;charset=UTF-8"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                answer = json.loads(response.read().decode("utf-8", "replace") or "{}")
        except (urllib.error.HTTPError, urllib.error.URLError, socket.timeout, OSError,
                ValueError):
            return {"ok": False,
                    "error": "CoreAlign could not save the project right now. Wait for the "
                             "current step to finish, then try again."}, 409
        if not isinstance(answer, dict) or not answer.get("ok"):
            return {"ok": False,
                    "error": "CoreAlign could not save the project right now. Wait for the "
                             "current step to finish, then try again."}, 409
        # The bridge flushes the operator's edits; it does not build the QuPath project. That
        # is written when the run reaches the project step. Re-read the folder rather than
        # trusting the bridge's ok: reporting success here would name an empty folder and
        # unlock Open in QuPath against it, which is the one thing that path must never do.
        if not (qupath_folder.is_dir() and any(qupath_folder.glob("*.qpproj"))):
            return {"ok": False,
                    "error": "Your edits are saved. The QuPath project itself is written when "
                             "the run reaches the project step, so there is nothing to open in "
                             "QuPath yet."}, 409
        return {"ok": True, "path": str(qupath_folder)}, 200

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
            extra = None
            if relative.startswith("qc/03-arrange/tiles"):
                # Tiles are immutable for one prepared run. This map redraws 117 cores across
                # 19 channels repeatedly, so making the browser fetch them again is wasteful.
                extra = {"Cache-Control": "public, max-age=31536000, immutable"}
            return self._send(200, resolved.read_bytes(), kind, extra)
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
