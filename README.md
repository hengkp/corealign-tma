# CoreAlign TMA

Rotate first. Crop second. Review what matters.

CoreAlign TMA is a configurable and resumable QuPath workflow for detecting TMA grids, orienting each skin core with the epidermis at the top, and exporting presentation ready PNG files plus rotated multichannel OME TIFF files.

Website: [hengkp.github.io/corealign-tma](https://hengkp.github.io/corealign-tma/)

Config Builder: [hengkp.github.io/corealign-tma/config-builder](https://hengkp.github.io/corealign-tma/config-builder/)

Complete tutorial: [CoreAlign TMA complete tutorial in 1080p](https://github.com/hengkp/corealign-tma/releases/download/v1.0.0/CoreAlign-TMA-complete-tutorial-1080p.mp4)

## What it does

- Runs from one production script: `workflow/CoreAlign.groovy`
- Detects the grid and asks for review before processing
- Refines, orients, rotates, checks, and crops each core in that order
- Saves an atomic checkpoint after every core
- Resumes only failed or corrected cores
- Exports full resolution RGB PNG and rotated UINT16 multichannel OME TIFF
- Supports profiles for other arrays, stains, scanners, and experiments

## Requirements

- QuPath 0.7.x
- A local whole slide image supported by QuPath
- Enough free disk space for all selected channels and outputs

## Quick start

1. Download this repository as a ZIP file and extract it.
2. Open the [Config Builder](https://hengkp.github.io/corealign-tma/config-builder/) and download `corealign.config.json`.
3. Copy `workflow/CoreAlign.groovy` and the config file beside the slide.
4. Open the slide in QuPath.
5. Open `Automate`, then `Show script editor`.
6. Open `CoreAlign.groovy` and press `Run`.
7. Review the detected grid and run the same file again.
8. Review uncertain orientations, add an override only where needed, and resume.
9. Approve the final reviewed result to create the presentation package.

## Video tutorial

The complete 3 minute 54 second walkthrough contains ten chapters from GitHub download through QuPath review and final exports. It includes ElevenLabs narration, an original ElevenLabs Music soundtrack, embedded English subtitles, and embedded Thai subtitles enabled by default.

The reproducible render recipe is in `tutorial/render_video.sh`. Narration and raw screen recordings remain local and are excluded from Git. The finished video is distributed as a GitHub Release asset.

## Important config fields

| Key | Purpose |
|---|---|
| `grid.rows`, `grid.columns` | Array dimensions |
| `grid.coreDiameterMM` | Physical punch diameter |
| `nuclearChannelTokens` | Words used to find DAPI like channels |
| `epidermisChannelTokens` | Markers that support skin orientation |
| `exportDownsample: 1` | Preserve source pixel dimensions |
| `saveFullResolutionPng` | Save a rotated lossless RGB PNG |
| `saveRotatedMultichannelOmeTiff` | Apply the accepted rotation to every original channel |
| `postRotationToleranceDeg` | Residual angle that triggers review |

For a new experiment, duplicate a profile, change `activeProfile`, and update the array geometry and channel tokens. Do not copy slide specific missing positions into a new profile.

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
