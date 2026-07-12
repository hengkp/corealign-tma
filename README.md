# CoreAlign TMA

Rotate first. Crop second. Review what matters.

CoreAlign TMA is a configurable and resumable QuPath workflow for detecting TMA grids, orienting each skin core with the epidermis at the top, and exporting presentation ready PNG files plus rotated multichannel OME TIFF files.

## Current workflow: v1.4.0

Version 1.4.0 estimates the array region, physical core size, rows, columns, and usable detection channels from the slide. A config file is optional. New slides must pass structural confidence checks before CoreAlign creates or approves a grid.

[Open the optional Config Builder](https://hengkp.github.io/corealign-tma/config-builder/) | [Download the latest release](https://github.com/hengkp/corealign-tma/releases/latest)

Website: [hengkp.github.io/corealign-tma](https://hengkp.github.io/corealign-tma/)

Config Builder: [hengkp.github.io/corealign-tma/config-builder](https://hengkp.github.io/corealign-tma/config-builder/)

Complete tutorial: [validated written tutorial](tutorial/README.md) and [version 3 video in 1080p](https://github.com/hengkp/corealign-tma/releases/download/v1.2.0/CoreAlign-TMA-tutorial-v3-1080p.mp4)

## What it does

- Runs from one production script: `workflow/CoreAlign.groovy`
- Estimates core diameter, row count, and column count without operator input
- Creates an automatic config when no config is supplied
- Detects the grid and asks for review before processing
- Refines, orients, rotates, checks, and crops each core in that order
- Saves an atomic checkpoint after every core
- Resumes only failed or corrected cores
- Exports full resolution RGB PNG and rotated UINT16 multichannel OME TIFF
- Supports skin and other TMA tissues without array geometry input

## Requirements

- QuPath 0.7.x
- A local whole slide image supported by QuPath
- Enough free disk space for all selected channels and outputs

## Quick start

1. Download the latest release and create a new empty working folder.
2. Put the slide and `workflow/CoreAlign.groovy` in that folder. A config is optional.
3. Open that copy of the slide in QuPath. Do not open a different copy from Downloads.
4. Open `Automate`, then `Show script editor`.
5. Open `CoreAlign.groovy` and press `Run`.
6. CoreAlign creates a config if needed, detects the array and core size, and writes the QC result without asking for geometry.
7. Review the detected grid and run the same file again.
8. Review uncertain orientations, add an override only where needed, and resume.
9. Approve the final reviewed result to create the presentation package.

## Validated tutorial

Follow [the current tutorial](tutorial/README.md). It includes the one-folder rule, the exact preflight checks, expected results for the example slide, review gates, resume behavior, and realistic PNG and OME-TIFF timing.

The [version 3 video](https://github.com/hengkp/corealign-tma/releases/download/v1.2.0/CoreAlign-TMA-tutorial-v3-1080p.mp4) is 4 minutes 9 seconds and contains the simplified Config Builder, English narration, and selectable English and Thai subtitle tracks.

Tutorial version 1 is retained only under `_archieved/tutorial-v1` because it did not prevent a wrong profile and core diameter from being mixed in QuPath.

## Optional config choices

| Choice | Purpose |
|---|---|
| Tissue | Use epidermis-up rotation for skin or peripheral-edge rotation for other tissue |
| Output | Create presentation PNG only or PNG plus multichannel OME-TIFF |
| Channel words | Optional help for unusual channel names |

Rows, columns, punch size, and layout are automatic. Use the Config Builder only when tissue type, output format, or unusual channel names need to change.

## Human overrides

- `TMA correction 4-C`: mark the correct core center
- `TMA mark missing 14-G`: mark a truly empty position
- `TMA crop override 4-C`: define the source region to rotate and crop
- `Epidermis override 4-C`: point to the epidermal side

Overrides are part of each core signature. A new run invalidates only the affected checkpoint.

## Outputs

```text
tma_auto_orient_export/<image>_grid_<hash>_orient_<run-id>/
  rotated_previews/
  rotated_fullres/
  rotated_multichannel_ome/
  source_native_ome/
  checkpoints/
  orientation_results.csv
  orientation_review_queue.csv
  orientation_contact_sheet.png
  review.html
  run_manifest.json
```

The rotated multichannel OME TIFF uses the same accepted transform for every original channel. Keep the original whole slide as the immutable source of truth.

## Accuracy policy

Reference slide technical validation assigned 126 of 126 positions with zero processing errors. The set contained 117 present cores and 9 known empty positions. This does not prove autonomous biological accuracy on unseen slides.

One hundred percent in this workflow means every position is accepted after human review and the exact grid and result hashes are approved. See [the validation protocol](docs/ACCURACY_99_9_PROTOCOL.md).

## Safety

This is research and quality control software. Independent pathologist review and local validation are required before clinical claims. Do not publish patient identifying slide data or private annotations.

## Credits

See [CREDITS.md](CREDITS.md). CoreAlign uses Remix Icon and draws design process inspiration from the resources listed there. No Flaticon or Lottie asset is redistributed without an explicit compatible license.
