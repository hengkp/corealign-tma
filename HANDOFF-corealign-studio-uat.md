# Handoff · CoreAlign Studio v2.13.0, finish the UAT on a real slide

Written 6 Sep 2026. Paste everything below the line into a new chat.

The redesign is **built, tested, committed, tagged and deployed**. What is left is the part
no synthetic fixture can do: run it on a real slide and confirm the four paths that only
exist during a live run. Nothing below is a known bug. It is a list of things not yet proven.

---

[Role]
You are working on **CoreAlign Studio**, a headless-QuPath web app that prepares TMA slides
on the SiSP Slurm cluster. Two repositories are involved and they are not the same one:

- **App source** · `hengkp/corealign-tma` on GitHub, cloned at
  `/Users/heng/Dropbox/Macbook Pro/Projects/Spatial project/CyCIF (NY&Folk)/corealign-tma`
  Three files matter: `studio/static/index.html` (the whole UI, ~5300 lines, plain ES5-style
  JS, no framework, no build step), `studio/corealign_studio.py` (standard library only), and
  `workflow/CoreAlign.groovy` (generated · edit `workflow/embedded/*.src` then
  `npm run workflow:embed`).
- **Container defs** · `sisp-nucleus` at
  `/Users/heng/Dropbox/Macbook Pro/Documents Heng/SiSP/sisp-nucleus/nucleus/apphub/images/`
  holds `corealign-studio.def` and `corealign-studio-refresh.def` only.

Read `studio/UX-REDESIGN.md` first. It is the design document the current release was built
from and it explains why each change exists.

[Task]
Take CoreAlign Studio v2.13.0 through a real end-to-end run on an actual slide, confirm the
four unproven paths listed below, fix whatever that surfaces, and then tell the people who
use it. Do not start new UI work until the run has passed.

## What shipped in v2.13.0 (all verified, do not redo)

| Change | State |
|---|---|
| Five stages: slide, tissue, cores, orientation, figure. Processing folded into the stage that owns it | done |
| Step 2 asks one question. Output-type choice and channel picker removed; every run writes the research set, so `qupath/project.qpproj` always exists | done |
| Autosave with a flush barrier: the primary button is never disabled or relabelled for save state, and `flushSaves()` must resolve before the gate POST | done |
| Row and column labels on every grid circle | done |
| Ghost circles at every empty lattice position · click to add, Delete to remove | done |
| Per-core resize, 4 handles plus a diameter field clamped to 20-300% of nominal | done |
| Backend fix: step 3 line 577 was discarding the operator's diameter and forcing `nominalDiameter` | done |
| Step 4 responsive card grid with filter chips, S/M/L density, bulk select | done |
| Menu bar with File (Save project, Open in QuPath), Export, always present | done |
| Picker copy names `.qptiff` and `.ome.tiff` | done |

Commits `9621d5d` and `05ea0ae`, tag **`v2.13.0`**, pushed. Deployed to
`/mnt/allflash/apphub-images/corealign-studio.sif` and the sisplockers copy, with
`*.bak-v2.12.0-20260906-150517` kept in both for rollback.

## 🔴 What is NOT verified · this is the actual job

Everything so far was proven with **synthetic models injected into the page**, plus a live
launch that confirmed the right build is being served. No slide has been through it.

1. **A real detection run.** Pick a `.qptiff` or `.ome.tiff` in a writable folder, run step 2
   to step 3, and confirm the grid gate opens with circles, labels and ghosts drawn from
   `*_grid_geometry.json`. Watch for ghosts rendering at the nominal size rather than the
   twentieth-of-a-core placeholder.
2. **Autosave actually round-trips.** Move a circle, wait ~1 s, and confirm
   `corealign-grid-corrections.json` appears beside the slide with the edit in it, and that
   the menu bar says Saved. Then press the primary button and confirm from the log that the
   write completed **before** the gate was answered. This is the single most important
   behaviour in the release and it has only ever been exercised against a 409.
3. **The resize reaches the grid.** Resize a core, save, continue, and confirm the approved
   grid CSV (`qc/01-grid/*coordinates*.csv`, column `diameter_px`) carries the operator's
   value and not the grid median. This is the backend fix and it has never run.
4. **Save project against a live bridge.** `POST /api/project/save` has only been tested on
   the no-project path (correctly returns 409 with a readable sentence). With a run in
   flight it routes through `RUN.bridge_url("save")`. Confirm it returns a real path and that
   opening that folder in QuPath desktop works.

Two more, lower risk but untouched by this release:

5. **Step 5 (figure) after the stage rename.** The arrange editor was re-homed from
   `data-panel="arrange"` to `data-panel="figure"` and wired to autosave. Its tests pass, but
   nobody has built a figure and exported a PNG since. Do that once.
6. **Tell the users.** `haymaro`, `supawanj`, `thanaphon`, `dianap` and `kriengkraip` all ran
   corealign-studio in the last few days. The flow they knew has changed shape. Say what
   changed, in one short message, once the run above passes.

## Traps that will cost you a session if you do not know them

- **The def files lie about the version.** `build-images.sh` rewrites `COREALIGN_REF` at build
  time, so a committed def drifts. Judge from the image:
  `ssh sisp-node3 "apptainer exec /mnt/allflash/apphub-images/corealign-studio.sif cat /opt/corealign/VERSION"`
- **Deploy with the refresh def, not the canonical one.** The canonical def re-downloads
  QuPath's 400 MB tarball and took over two hours at lab uplink speed. The refresh def
  bootstraps from the deployed `.sif` and swaps three text files in under a minute.
  `build-images.sh` has no refresh target · write the def into a tmpdir on node3 and run
  `apptainer build --fakeroot` by hand.
- **`cp -a` fails on `/mnt/sisplockers/.apphub-images`** with *"preserving permissions:
  Operation not supported"*. Use plain `cp` then `chmod`.
- **node1 cannot reach the app's own public hostname** (hairpin NAT). To verify a launch, get
  the port with `ssh sisp-node2 "sudo -n ss -lntp | grep python"` and fetch
  `http://192.168.0.26:<port>/` from node1. Comparing the served page's sha256 against the
  repo is what proves which build is live.
- **corealign-studio is not in the node-local image cache**, so an install on allflash is
  live immediately and no manifest republish is needed.
- **Never hand-edit the embedded Groovy inside `CoreAlign.groovy`.** Edit
  `workflow/embedded/*.src`, then `npm run workflow:embed`, then `npm run workflow:verify`.
- **The i18n dictionaries must have identical key sets** (English near line 1440, Thai near
  1740). A test enforces it. Every new string needs both.
- **`HANDOFF-save-angle-edits.md` in the repo root is stale.** Its bug was fixed in `6e8b89e`.
  Delete it or ignore it; do not act on it.

[Format]
Report what you actually ran and what it returned, pasted, not summarised. If any of the four
unproven paths fails, say which one, show the evidence, then fix it and re-run the whole set.

[Specification · done when all of these hold]
1. A real slide reaches the grid gate with labels and ghosts drawn correctly.
2. A grid edit lands in `corealign-grid-corrections.json` without anybody pressing Save, and
   the gate is answered only after that write returns.
3. A resized core's diameter survives into the approved grid CSV.
4. `POST /api/project/save` returns a real path during a live run, and QuPath desktop opens it.
5. A figure exports a PNG from step 5.
6. `node --test tests/studio-page.test.mjs tests/workflow-channels.test.mjs` still reports
   64 pass 0 fail, `npm run workflow:verify` still shows 8 ROUNDTRIP OK, `node
   scripts/ui-harness.mjs` still prints COREALIGN_UI_HARNESS_PASSED, and `npm run lint` is clean.

[Constraints]
- Standard library only in `corealign_studio.py`. No framework, bundler, CDN link or web font
  in `index.html`: it ships in an Apptainer image on an offline cluster.
- It is unacceptable to delete, skip or weaken a test to make the suite pass. If a test
  encodes behaviour that is genuinely being changed, rewrite it and say why.
- No em dash anywhere. Use a middle dot, a colon, or a new sentence.
- Status is never carried by colour alone: colour plus icon plus words.
- Preserve everything under "What must not regress" in `studio/UX-REDESIGN.md`, in particular
  that the bridge token never reaches the browser and that a correction file stays tied to the
  grid hash it was made against.
- Any change ships the whole way: commit, push, tag, rebuild through the refresh def, install
  to both image dirs with a backup, and confirm the served sha256.

[Feedback Loop]
```bash
cd "/Users/heng/Dropbox/Macbook Pro/Projects/Spatial project/CyCIF (NY&Folk)/corealign-tma"
node --test tests/studio-page.test.mjs tests/workflow-channels.test.mjs
npm run workflow:verify
node scripts/ui-harness.mjs
npm run lint
```
Local page QA without the cluster:
```bash
COREALIGN_STUDIO_ROOTS=/tmp PORT=8899 python3 studio/corealign_studio.py
```
