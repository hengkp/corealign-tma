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

`REPORT.html` is reused as CoreAlign writes it, with the loopback URLs rewritten to Studio's own
`/api/bridge/...` paths. Every control in the report keeps working, and the bridge token stays on
the node: it is never sent to the browser. That makes this less exposed than the desktop
arrangement, not more.

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
| `POST /api/bridge/<action>` | forwards one control to CoreAlign's bridge |
| `GET /project/REPORT.html` | the report, loopback URLs rewritten |
| `GET /project/<path>` | QC images and tables, confined to the project folder |
| `GET /api/results` | what the run has produced, folder by folder |
| `GET /api/download?path=` | one file, confined to the project folder |
| `GET /api/download-zip?group=` | one result folder, or `all`, streamed |
