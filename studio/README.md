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

## Correcting the grid without QuPath

The grid gate used to say: draw an ellipse over the missed core in QuPath and name it
`TMA correction`. For somebody driving Studio that is a dead end, because the whole point of the
app is that there is no desktop to draw in.

The QC overlay is interactive instead. The circles are drawn as SVG over the overlay image, and:

| To do this | Do that | It becomes |
|---|---|---|
| move a circle onto the tissue | drag it | `TMA correction <core>` |
| say a position is empty | select it, press the button in the rail | `TMA mark missing <core>` |
| say a position marked empty has tissue | drag its circle onto the tissue | `TMA correction <core>` |

Saving writes `corealign-grid-corrections.json` beside the slide. Step 3 turns each entry into
the annotation a person would have drawn, so corrections take one code path whether they were
drawn by hand in QuPath or clicked in a browser. Answering the gate then applies them and reopens
it on the corrected grid for a second look.

**The file names the grid hash it was made against**, and applying it changes that hash. That is
what stops the same file being applied twice: inserting a core shifts the later ones along, and
doing that a second time would quietly corrupt the row labels. A file made against a grid that
has since moved on is skipped with a line saying so.

**A correction that cannot be applied stops the run.** It joins `correctionErrors`, which is a
hard error and blocks approval. Approving a grid while discarding edits somebody just made and
pressed a button to apply would look like success and be wrong: that is exactly what happened
when the first version called `putMetadataValue` on an annotation, which QuPath 0.7 does not
have, and lost three corrections to a caught exception.

### Where the numbers come from

`*_grid_qc_latest.json` is written by step 3, which runs **after** the grid gate is answered. On
a slide that has never been through CoreAlign the first gate therefore had nothing to draw: no
image, no circles, no counts. It was invisible in testing because a project that has been run
before still holds the file from last time.

The runner now writes `*_grid_geometry.json` every time the grid gate opens, from the grid that
is on screen: the circles, the counts, `overviewWidth`/`overviewHeight` and
`slideWidth`/`slideHeight` for the mapping, and the grid hash. Studio takes positions from that
and the QC commentary from step 3's file when it exists.

## How fast a run is: workers come from the allocation

Cores are independent of each other, so orientation processes them on a thread pool. That pool
used to be a fixed 2 while the AppHub job held 8 CPUs, and the runner capped any request at 4.

Measured on node3 on 23 Aug 2026, on the 117-core reference slide, steady state with warm-up
excluded:

| Workers | Per core | 117 cores | |
|---|---|---|---|
| 2 | 21.0 s | 40.9 min | the old fixed default |
| 8 | 6.0 s | 11.7 min | |
| 16 | 6.1 s | 11.9 min | no better, and it needs the heap cap below |

**It plateaus at 8.** Past that the extra workers wait on something shared, most likely the read
path to the NAS, and only add memory pressure: each worker holds its own full-resolution crop on
top of QuPath's shared tile cache. `orientation_workers()` therefore takes the smaller of the CPU
allocation less one, the memory allocation divided by 2 GB per worker after 8 GB of JVM headroom,
and a ceiling of 8. The AppHub default is 12 CPUs and 48 GB, which lands exactly on 8 workers.

Research OME-TIFF output still runs one core at a time, because the Bio-Formats writer is not
thread safe.

### The JVM was sizing itself from the node, not the job

A 16-worker run was killed by Slurm at 94 GB of a 96 GB allocation. The cause was in the log all
along: *Setting tile cache size to 64456.00 MB (25.0% max memory)*. Java was not seeing the job's
cgroup limit, so it sized its heap, and therefore QuPath's tile cache, from the node's 503 GB. At
two workers the cache never grew that far and nothing looked wrong.

The AppHub runner now exports `APPHUB_MEMORY_MB` and passes `-Xmx` at 70% of the allocation. The
same 16-worker run then reported an 11,472 MB tile cache and peaked at 51 GB instead of 94 GB, so
it was not killed. Anyone running Studio outside AppHub should set `-Xmx` themselves for the same
reason.

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
