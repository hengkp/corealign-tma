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

**The bridge only exists while the QuPath that opened it does.** Both places its address is
written down outlive the run: `REPORT.html` stays in the project for good, and `gate.json`
survives any kill that skips CoreAlign's finally block. A project that finished hours ago
therefore still names a loopback port, and posting to it fails with a bare *Connection
refused* that reads like a network fault. Studio resolves that address only while its own
QuPath is running and treats it as absent otherwise, which is what sends a saved angle edit
into `corealign-review-corrections.json` on a finished project rather than into an error, and
what makes the controls say *CoreAlign is not listening for that right now*. A bridge that
dies under a run that is still going is a different fault and still shows up as one.

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

## 2 Arrange tile sets, 3 export controls, 1 display range

256 px screen tiles live in `qc/03-arrange/tiles` and keep the Arrange grid quick to load.
1280 px export tiles live in `qc/03-arrange/tiles-hires` and are loaded only by figure preview
and PNG export. Older projects with only the 256 px set keep their original export behaviour.

The active profile's `arrange` object controls the second set:

| Key | Default | Effect |
|---|---:|---|
| `exportTilePx` | `1280` | Sets the saved tile width and height in pixels. |
| `exportDownsample` | `2` | Sets the source read downsample for the export set. |
| `exportEnabled` | `true` | Cuts and advertises the export set when enabled. |

1 coarse overview measures each channel's low and high display values. Both tile sets reuse
those values so the same figure window produces the same intensities at either resolution, and
the run avoids a second overview read that could cost time and measure a different range.

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

Cores are independent of each other, so orientation processes them on a thread pool.

Re-measured end to end on node2 on 26 Aug 2026, on the 126-position reference slide, one run
at a time so they did not contend for the same NAS read path:

| Workers | CPUs | Memory | Step 2 | Peak RSS |
|---|---|---|---|---|
| 8 | 12 | 48 GB | **987 s** | 38 GB |
| 16 | 20 | 96 GB | **662 s** | 75 GB |
| 32 | 36 | 160 GB | **566 s** | ~150 GB |

🔴 **It does not plateau at 8, and an earlier version of this file said it did.** That claim
came from a 23 Aug measurement whose 16-worker arm was killed by Slurm before it could be
judged; the conclusion was drawn from the two arms that survived. Doubling from 8 to 16 is
worth 1.49x and doubling again is worth a further 1.17x · diminishing, but real.

Peak memory tracked about **5 GB per worker** at every point, not the 2 GB the guard assumed,
which is why it would let a job ask for far more workers than its allocation could hold.
`orientation_workers()` takes the smaller of the CPU allocation less one, the memory allocation
less 8 GB of JVM headroom divided by 5 GB per worker, and a ceiling of 32 · which is also the
AppHub template's CPU limit. The model reproduces each measured-safe point: 12 CPUs and 48 GB
gives exactly 8.

The AppHub default is **24 CPUs and 128 GB**, which lands on 23 workers.

Research OME-TIFF output still runs one core at a time, because the Bio-Formats writer is not
thread safe.

### Where the rest of the time goes, and why 3 to 5 minutes is not reachable here

The other knob that matters is `orientation.exportDownsample`. Measured at 16 workers on the
same slide:

| Export | Step 2 | Per core | Output |
|---|---|---|---|
| full resolution | 662 s | – | 2662 x 2662 px, 395 MB |
| half resolution | **366 s** | – | 1331 x 1331 px, 81 MB |

**1.81x**, which puts the export path at roughly 60% of the per-core cost and the orientation
analysis at the other 40%. Combining the best of both · 32 workers and half resolution ·
extrapolates to about 313 s of orientation plus a 105 s grid step, so **about 7 minutes**.

Full resolution at 32 workers is **11 minutes**. Neither reaches 3 to 5 minutes for a
126-position slide, and saying otherwise would be a guess rather than a measurement. Quarter
resolution would land near 6 minutes and produce 665 px cores, which is too small to be worth
it.

Two things are worth profiling before promising anything faster. The grid step is a flat
105 to 125 s and is single-threaded, which is a quarter of a 7-minute run. And the per-core
analysis · four independent estimators, each scoring the same core · has never been timed
against the export path it shares a loop with.


### The JVM was sizing itself from the node, not the job

A 16-worker run was killed by Slurm at 94 GB of a 96 GB allocation. The cause was in the log all
along: *Setting tile cache size to 64456.00 MB (25.0% max memory)*. Java was not seeing the job's
cgroup limit, so it sized its heap, and therefore QuPath's tile cache, from the node's 503 GB. At
two workers the cache never grew that far and nothing looked wrong.

The AppHub runner now exports `APPHUB_MEMORY_MB` and passes `-Xmx` at 70% of the allocation. The
same 16-worker run then reported an 11,472 MB tile cache and peaked at 51 GB instead of 94 GB, so
it was not killed. Anyone running Studio outside AppHub should set `-Xmx` themselves for the same
reason.

## Choosing the channels

The operator picks which of the slide's channels the run keeps. Those are the channels that
end up in the presentation picture and in the exported OME-TIFF, and they are the ones the
rotation is computed from.

🔴 **This is not a speed feature, and an earlier version of this file said it was.** The claim
came from a benchmark that always read all nineteen channels first and the six-channel subset
second, at the same coordinates · the subset was reading a warm cache and looked five times
faster. Re-run on fresh windows with the order alternating, on 26 Aug 2026:

| Channels read | Per read, steady state |
|---|---|
| 19 | 5.1–5.6 s |
| 6 | 5.1–5.7 s |

**Ratio 1.18, inside the noise.** `TransformedServerBuilder.extractChannels` reads the whole
region from the underlying server and copies the wanted bands out of it, so Bio-Formats still
decodes every plane. Two full runs on the same slide, same node, same 8 workers, said the same
thing end to end: **15:27 with all nineteen channels, 18:28 with six.** The subset is slightly
slower, which is the extra band copy.

Keep the feature for what it is: control over what the run delivers. Do not sell it as speed.

Channel names cost one seek, not a JVM. An OME-TIFF keeps its OME-XML in TIFF tag 270 of the
first IFD: **0.155 s on the 28 GB reference slide**, and the names came back identical to
QuPath's. A `.qptiff` falls back to its `ScanColorTable` entries. A slide whose header says
nothing hides the question, and that run behaves exactly as it did before this feature existed.

🔴 **The channel view reports FLOAT32 even when the slide is UINT16**, and that broke the one
output mode this feature exists to control. The OME writer asked the view what the pixel type
was and refused every core of a research run with *"supports UINT8/UINT16; found FLOAT32"* ·
117 cores, zero files. The samples were never wrong: checked on 4.86 million of them across six
channels, every value bit-exact against the full UINT16 read and every one exactly integral.
The view relabels the type, it does not rescale. The guard asks `sourceServer` now, so a
genuinely floating-point slide is still refused. Fixed in v2.6.5.

A note for whoever revisits this. The subset buys nothing in speed, and it has now cost three
separate bugs · the approval check, the pixel type, and the identity hashes · all of them from
the same root, which is that a transformed server is a different thing wearing the slide's
clothes. Reading the full server and selecting channels only where output is produced would
avoid the whole class. It is a bigger change than there was room for, and worth doing if this
area is touched again.

The setup screen asks it collapsed to one line, with every channel checked. Selecting every
channel sends nothing at all: the selection is part of both identity hashes, and a list saying
"all of them" would invalidate every core a previous run had already computed.

`orientation.channelIndices` carries the answer: 0-based indices into the slide's own channel
list, absent or empty meaning every channel. Step 2 swaps the server for a
`TransformedServerBuilder(...).extractChannels(...)` view once, immediately after the channel
names are first read, and re-reads the names from it. Every positional channel index downstream
is computed after that point, so they all land in the subset's space without a manual remap.

Two things this touched that are easy to get wrong. `imageStem` decides the state and output
directory names and now comes from the original server, because a transformed server reporting
a different name or path would silently orphan every checkpoint. And the approval check
compares the slide's name and size against Step 3's checkpoint · reading those from the channel
view blocked every channel-selected run at *"the current grid does not match its approved
checkpoint"* before a single core was processed. The rule is written where the swap happens:
**sourceServer identifies the slide, server reads its pixels.**

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

## Results and export: the report is the screen

The results screen used to be a list of folders with download buttons, which meant the only way
to find out how a run came out was to fetch a zip, unpack it, and open the generated report in
another tab. It is now the report itself, drawn from `/api/review`: filter chips with live
counts, a gallery of every core, and a detail view with the unrotated and rotated crops side by
side, the angle, the confidence, the residual and the reasons. The results table is fetched and
parsed in the browser and rendered in place.

The gallery is built from the 900 px previews under `qc/02-orientation`, never from
`results/png` · the full-resolution files are about 3.2 MB each and 377 MB per run. The
previews are `loading="lazy"` and `fetchpriority="low"`; the two images in the detail view are
eager and high, because 126 thumbnails will otherwise saturate the link and leave the one thing
the person actually clicked for waiting behind them.

One CSS trap worth remembering, found by looking at the rendered page rather than at the code:
`width` and `height` attributes on an image carry a real presentational `height`, which beats
`aspect-ratio`. Without `height:auto` every tile rendered 900 px tall and one screen became an
18,016 px scroll.

Downloads still work, one fold down. Studio lists the output folders with a count and a size and
offers each as a zip plus one zip of everything. Files live on the cluster and the person is on a
laptop, so this is still the last mile.

The zip is streamed with chunked encoding rather than built in a temp file: a research run's PNG
folder can be larger than anything this job should be writing twice. It is stored, not deflated,
because PNG and OME-TIFF are already compressed and deflating them would spend CPU on a shared
node to save almost nothing.

## Endpoints

| Route | What it does |
|---|---|
| `GET /api/roots`, `GET /api/browse?path=` | the slide picker, sandboxed to the allowed roots with symlinks resolved |
| `GET /api/channels?slide=` | the slide's channel names, read from the TIFF header without opening the slide |
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
