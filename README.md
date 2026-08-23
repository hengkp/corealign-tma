# CoreAlign TMA

Rotate first. Crop second. Review what matters.

CoreAlign TMA is a configurable and resumable QuPath workflow for detecting TMA grids, orienting each skin core with the epidermis at the top, and exporting presentation ready PNG files plus rotated multichannel OME TIFF files.

## Current workflow: v2.0.0

**One run. Two questions. No re-runs.**

Up to v1.9 a slide took three or four separate presses of Run, eight modal dialogs, and a visit
to a separate website to produce a config file. v2.0.0 replaces all of that with one setup
dialog and two review gates:

| | v1.9 | v2.0 |
|---|---:|---:|
| Times you press Run per slide | 3 to 4 | **1** |
| Modal dialogs to read and dismiss | 8 | **1** |
| Clicks to configure a normal slide | 6 plus a website visit and a file move | **0 to 2** |
| Places a review can be answered | QuPath script editor only | **REPORT.html or QuPath** |

When CoreAlign needs a person it opens REPORT.html, shows the evidence, and waits. Answering
in the browser continues the same run. Nobody returns to the script editor.

[Read the manual](https://hengkp.github.io/corealign-tma/guide/) | [Download the latest release](https://github.com/hengkp/corealign-tma/releases/latest)

Website: [hengkp.github.io/corealign-tma](https://hengkp.github.io/corealign-tma/)

Manual: [hengkp.github.io/corealign-tma/guide](https://hengkp.github.io/corealign-tma/guide/) ·
Thai: [docs/USER_GUIDE.th.md](docs/USER_GUIDE.th.md)

Design: [Moodboard](https://hengkp.github.io/corealign-tma/moodboard/) ·
[Playbook](https://hengkp.github.io/corealign-tma/playbook/)

Config Builder: [hengkp.github.io/corealign-tma/config-builder](https://hengkp.github.io/corealign-tma/config-builder/) ·
optional, and only for unusual channel names.

Reference: [hengkp.github.io/corealign-tma/docs](https://hengkp.github.io/corealign-tma/docs/)

## What it does

- Runs from one production script: `workflow/CoreAlign.groovy`
- Estimates core diameter, row count, and column count without operator input
- Uses a slightly generous 1.90x core footprint with a spacing cap so circles cover peripheral tissue without touching adjacent cores
- Creates an automatic config when no config is supplied
- Detects the grid and asks for review before processing
- Refines, orients, rotates, checks, and crops each core in that order
- Saves an atomic checkpoint after every core
- Resumes only failed or corrected cores
- Refreshes one `REPORT.html` dashboard and writes machine-readable JSON and CSV audit data
- Shows a modal result summary when processing pauses or completes
- Exports full resolution RGB PNG and rotated UINT16 multichannel OME TIFF
- Uses the same display ranges for every PNG core from one slide
- Records the selected channels and ranges in `results/tables/display_ranges.json`
- Builds an analysis-ready QuPath project with row, column, QC, and transform metadata
- Supports skin and other TMA tissues without array geometry input

## Requirements

- QuPath 0.7.x
- A local whole slide image supported by QuPath
- Enough free disk space for all selected channels and outputs

## Quick start

1. Download the latest release. Put the slide and `workflow/CoreAlign.groovy` in one empty folder.
2. Open that copy of the slide in QuPath. Do not open a different copy from Downloads.
3. `Automate`, then `Show script editor`, then open `CoreAlign.groovy` and press `Run`.
4. Pick **Tissue** and **Results**, press **Start**. Rows, columns, core size, and array
   position are measured from the slide, so they are never asked for.
5. CoreAlign detects the array, then opens `REPORT.html` and waits. Check the circles and press
   **Grid is correct**. To fix a missed core, draw an ellipse over it in QuPath, name it
   `TMA correction`, and press the same button.
6. Cores are rotated and cropped one at a time, with a checkpoint after each one.
7. CoreAlign opens the report again. Confirm the cores that look right, use **Edit** on any
   wrong angle, then press **Approve and finish**.
8. Files are in `results/`. Research runs also produce `results/ome-tiff/` and an ordered
   QuPath project.

The small CoreAlign window in QuPath carries the same two buttons as the report, so a reviewer
who prefers to stay in QuPath never has to open a browser at all.

## Validated tutorial

Follow [the current tutorial](tutorial/README.md). It includes the one-folder rule, the exact preflight checks, expected results for the example slide, review gates, resume behavior, and realistic PNG and OME-TIFF timing.

Use [the production prompt](tutorial/VIDEO_SCRIPT.md) for the next screen recording. It locks the real click sequence, English narration, privacy setup, captions, and acceptance checks to the v1.4 workflow.

## How the review gates work

Both pauses run through a loopback HTTP bridge on `127.0.0.1` that only the local machine can
reach. The bridge is armed for exactly one gate at a time and delivers a decision exactly once,
so a report tab left open from an earlier run cannot advance a later one.

- The button in `REPORT.html` posts the decision to the bridge.
- The button in the CoreAlign window sets the same decision directly.
- Either way the run continues in place, with the approval recorded as human approval and a
  note saying it came from the report review gate.
- Closing QuPath during a gate is safe. Run the script again and it resumes from the last
  checkpoint.

Run `node scripts/ui-harness.mjs` to render both dialogs and assert that a gate decision is
delivered once and only once. Run `npm run workflow:verify` to prove that every embedded step
still matches its source in `workflow/embedded/`.

## Optional config choices

| Choice | Purpose |
|---|---|
| Tissue | Use epidermis-up rotation for skin or peripheral-edge rotation for other tissue |
| Output | Create presentation PNG only or PNG plus multichannel OME-TIFF |
| Marker channels | Optional channel names for presentation PNG files |

Rows, columns, punch size, and layout are automatic. Tissue and output are asked in the setup
dialog when you press Run. The Config Builder is only needed for unusual channel names.

### Upgrade PNG results to a research package later

1. Keep the original slide folder and its `work` folder. For a project created before v1.6, also keep any legacy `tma_pipeline_state` and `tma_auto_orient_export` folders until CoreAlign has resumed it successfully.
2. Open `REPORT.html`, select Results, and choose `Research`. Choose the project folder if the browser asks. QuPath does not need to stay open while you review or save.
3. Return to the same original slide in QuPath and run the same `CoreAlign.groovy` file.

CoreAlign uses separate processing and output identities. If only the output choice changed, it reuses the approved grid, refined core region, accepted rotation angle, and final crop. It reads the source slide and creates only the missing rotated multichannel OME-TIFF files. It does not redetect or reorient the cores. If an export stops, run the same script again and it resumes the missing files.

After final human approval, Research package mode creates `qupath/project.qpproj`. Each non-missing core is a separate multichannel image entry named in row-major order and tagged with its row and QC status. Open the project through `File > Project > Open project`. CoreAlign does not switch projects automatically because QuPath closes the current viewers when a different project is opened.

PNG files alone cannot be converted back into a multichannel research file. The original slide and checkpoint folders are therefore required. Do not rename or delete them until the research package is complete.

## Human overrides

- For the usual case, draw an ellipse over the missed or misplaced tissue and name or classify it `TMA correction`. Run CoreAlign again. It fits the array lattice, assigns the row and column, and shifts later labels into the next missing slot when one missed core caused an off-by-one row.
- If the position is genuinely ambiguous, use an explicit name such as `TMA correction 4-C`.
- `TMA mark missing 14-G`: mark a truly empty position
- `TMA crop override 4-C`: define the source region to rotate and crop
- `Epidermis override 4-C`: point to the epidermal side

CoreAlign uses the same action-first format for every object shown in the annotation list:

| Annotation name | Created by | ROI shape | Purpose |
|---|---|---|---|
| `TMA orientation 4-C` | CoreAlign | Ellipse | Refined rotate-then-crop footprint and orientation QC metadata |
| `TMA correction 4-C` | User, then named by CoreAlign | Ellipse | Correct a missed or misplaced core |
| `TMA mark missing 4-C` | User | Ellipse | Confirm an empty grid position |
| `TMA crop override 4-C` | User | Ellipse or rectangle | Replace the crop footprint |
| `Epidermis override 4-C` | User | Small ellipse or point | Identify the true epidermal side |

The old `<row-col> epidermis` line objects are removed automatically on the next run. TMA grid cores remain circular TMA objects; `TMA orientation <row-col>` is a separate ellipse annotation that shows the refined crop used by orientation and export.

Overrides are part of each core signature. A new run invalidates only the affected checkpoint.

When several `TMA correction` annotations are drawn before one run, CoreAlign applies them as one batch and refreshes the whole-slide detection overview. The latest files are `qc/01-grid/<image>_grid_qc_latest.png`, `<image>_grid_coordinates_latest.csv`, and `<image>_grid_qc_latest.json`. Cyan is automatically detected, green is human corrected, and red is missing. The PNG includes the current connecting lines and replaces the general `<image>_grid_qc.png`, so the visible QC always matches the current live grid.

## Outputs

```text
my-project/
|-- slide.ome.tif
|-- CoreAlign.groovy
|-- corealign.config.json
|-- REPORT.html
|-- corealign-review-corrections.json
|-- qc/
|   |-- 01-grid/
|   `-- 02-orientation/
|-- results/
|   |-- png/
|   |-- ome-tiff/
|   |-- tables/
|   `-- presentation/
|-- qupath/
|   |-- project.qpproj
|   `-- corealign_project_manifest.json
`-- work/
    |-- state/
    `-- runs/
```

Open `REPORT.html` to see the current stage and direct links. The `qupath` project is created only in Research package mode after final approval. The rotated multichannel OME TIFF uses the same accepted transform for every original channel. Keep the original whole slide as the immutable source of truth.

`work/` contains the run identity, checkpoints, and approvals required for safe resume. It is intentionally separated from the files a researcher normally opens. CoreAlign can read legacy v1.5 `tma_*` folders and publishes their current files into the v1.6 layout without repeating completed cores.

## Accuracy policy

CoreAlign does not use a filename-specific geometry or missing-core answer key. Technical accuracy must be measured against an independently adjudicated reference set, not against a result derived from the same detector.

One hundred percent in this workflow means every position is accepted after human review and the exact grid and result hashes are approved. See [the validation protocol](docs/ACCURACY_99_9_PROTOCOL.md).

## Safety

This is research and quality control software. Independent pathologist review and local validation are required before clinical claims. Do not publish patient identifying slide data or private annotations.

## Credits

See [CREDITS.md](CREDITS.md). CoreAlign uses Remix Icon and draws design process inspiration from the resources listed there. No Flaticon or Lottie asset is redistributed without an explicit compatible license.
