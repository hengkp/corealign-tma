# CoreAlign Studio

Prepare a TMA slide from a web page. No desktop, no VNC, no QuPath window.

```
open the app  ->  pick a slide  ->  two choices  ->  Start
              ->  check the grid, Continue
              ->  check the cores, Approve
              ->  files are in results/
```

## Why it exists

The desktop template streams a whole Linux desktop to the browser so a person can drive QuPath by
hand. That needs KasmVNC, a window manager, a window-fitting daemon and a browser inside the
image, and every one of them is a thing that can go wrong. Studio removes all of it: QuPath runs
headless in the Slurm job and the person only ever sees a web page.

## How the review gates reach the browser

Headless CoreAlign opens a loopback HTTP bridge on `127.0.0.1` inside the job. A reviewer's
browser is on their own laptop and can never reach that address, which is exactly why the desktop
template had to ship a browser in the container.

Studio proxies instead:

```
browser  ->  Studio (compute node, app port)  ->  CoreAlign bridge (127.0.0.1, same job)
```

The bridge token stays on the node and is never sent to the browser, which makes this less
exposed than the desktop arrangement, not more.

## The review screen is Studio's own

The first version embedded `REPORT.html` in an iframe. That was wrong: the report carries its own
sticky topbar and review bar, so on screen they stacked on top of Studio's and the page became
hard to read and hard to click.

The review screen is now drawn from `/api/review`, which reads the run's own JSON:
`qc/02-orientation/run_report.json` for the cores and `qc/01-grid/*_grid_qc_latest.json` for the
detected grid. One source of truth for the numbers, and the page can lay them out however reads
best. `REPORT.html` is still written by CoreAlign and still served at `/project/REPORT.html` with
its loopback URLs rewritten, so it stays a working artefact to keep or open elsewhere.

Layout comes from shipped products rather than invention (patterns via Mobbin): a queue on the
left with the item and its decisions on the right (Reddit mod queue), media on a dark stage with
the decision rail beside it (ClassDojo), a run header with state and duration over an event log
in a drawer (Databricks), filter chips and a count above the list (Frontify), a summary strip of
counts above the detail (AWS upload status), and a numbered stage tracker across the top (Zoho
CRM migration). One question per stage, one filled button per screen, the log collapsed by
default, and status carried by colour plus icon plus words rather than colour alone.

Picking a slide also attaches to whatever is already in that folder, so a finished run can be
opened and read without running it again.

## How fast a run is: workers come from the allocation

Cores are independent of each other, so orientation processes them on a thread pool. That pool
used to be a fixed 2 while the AppHub job held 8 CPUs, and the runner capped any request at 4.

Measured on node3 on 23 Aug 2026, on the 117-core reference slide, steady state with warm-up
excluded:

| Workers | Per core | 117 cores |
|---|---|---|
| 2 | 21.0 s | 40.9 min |
| 8 | 6.0 s | 11.8 min |

The gain is real but sublinear, and what runs out first is memory: each worker holds its own
full-resolution crop on top of QuPath's shared tile cache. `orientation_workers()` therefore takes
the smaller of the CPU allocation less one, the memory allocation divided by 2 GB per worker after
8 GB of JVM headroom, and a ceiling of 16. Research OME-TIFF output still runs one core at a time,
because the Bio-Formats writer is not thread safe.

## Running it outside AppHub

```bash
COREALIGN_QUPATH=/path/to/QuPath \
COREALIGN_WORKFLOW=/path/to/CoreAlign.groovy \
COREALIGN_STUDIO_ROOTS=/data/slides \
PORT=8848 python3 corealign_studio.py
```

| Variable | Default |
|---|---|
| `PORT` / `APPHUB_PORT` | `8848` |
| `COREALIGN_QUPATH` | `/usr/local/bin/QuPath` |
| `COREALIGN_WORKFLOW` | `/opt/corealign/CoreAlign.groovy` |
| `COREALIGN_STUDIO_ROOTS` | the person's `$HOME`, plus any mounted NAS share |
| `COREALIGN_STUDIO_BIND` | `0.0.0.0` |

Standard library only, on purpose: the image is QuPath plus a Python interpreter and nothing that
needs a package index at build time.

## Slide formats: everything goes through Bio-Formats

QuPath 0.7 bundles `openslide-4.0.0.6` and expects OpenSlide 4.x. Ubuntu 22.04 ships 3.4.1, so
installing the distribution package does **not** make `isOpenSlideAvailable()` true, and adding it
was a fix that was not one. The image therefore has no working OpenSlide.

In practice this is a performance question, not a capability one: Bio-Formats reads OME-TIFF, SVS,
NDPI, CZI, VSI and MRXS, so the picker's formats all open. Pyramidal whole-slide formats simply
read more slowly than they would through OpenSlide. OME-TIFF, which is what this lab produces, is
a Bio-Formats format anyway and is unaffected.

## The one constraint worth knowing

CoreAlign writes its results **beside the slide it opened**. Studio checks that the folder is
writable before it will start, and says so plainly rather than failing halfway. A slide on a
read-only share has to be copied into the person's locker first.

## Results and export

When a run produces files, Studio lists them by folder with a count and a size, and offers each
folder as a zip plus one zip of everything. Files live on the cluster and the person is on a
laptop, so this is the last mile.

The zip is streamed with chunked encoding rather than built in a temp file: a research run's PNG
folder can be larger than anything this job should be writing twice. It is stored, not deflated,
because PNG and OME-TIFF are already compressed and deflating them would spend CPU on a shared
node to save almost nothing.

## Endpoints

| Route | What it does |
|---|---|
| `GET /api/roots`, `GET /api/browse?path=` | the slide picker, sandboxed to the allowed roots with symlinks resolved |
| `POST /api/run` | writes `corealign.config.json` and starts QuPath headless |
| `GET /api/status` | run state plus the open gate, read from `work/state/<image>/gate.json` |
| `GET /api/log` | tail of the run |
| `POST /api/attach` | point at a project that already has results, without running anything |
| `GET /api/review` | the cores, the grid and any saved corrections, for the review screen |
| `POST /api/corrections` | angle changes; goes through the bridge when a run is waiting, straight to `corealign-review-corrections.json` when none is |
| `POST /api/bridge/<action>` | forwards one control to CoreAlign's bridge |
| `GET /project/REPORT.html` | the report, loopback URLs rewritten |
| `GET /project/<path>` | QC images and tables, confined to the project folder |
| `GET /api/results` | what the run has produced, folder by folder |
| `GET /api/download?path=` | one file, confined to the project folder |
| `GET /api/download-zip?group=` | one result folder, or `all`, streamed |
