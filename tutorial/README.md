# CoreAlign TMA tutorial

Validated on 12 July 2026 with QuPath 0.7.0 on the example `TMA_0.6mm_7_backsub.ome.tif` slide.

Video: [CoreAlign TMA tutorial version 3 in 1080p](https://github.com/hengkp/corealign-tma/releases/download/v1.2.0/CoreAlign-TMA-tutorial-v3-1080p.mp4)

## The one-folder rule

Create one new empty working folder. It must contain exactly these three files:

```text
CoreAlign-Run/
  TMA_0.6mm_7_backsub.ome.tif
  CoreAlign.groovy
  corealign.config.json
```

Open the slide from this folder. Do not open another copy of the slide from Downloads or a project folder. CoreAlign resolves the config, checkpoints, QC, and exports from the folder containing the open slide.

There must be only one config file. Delete or archive names such as `corealign.config (1).json` and `corealign.config (2).json`. CoreAlign now stops before processing if duplicates exist.

## Download and prepare

1. Download the latest GitHub Release.
2. Copy `workflow/CoreAlign.groovy` and `workflow/corealign.config.json` into the new working folder.
3. Copy or move the slide into the same folder.
4. For the example skin array, confirm the config contains profile `skin_18x7`, grid `18 x 7`, and core diameter `0.6 mm`.
5. Keep `showAdvancedDialog` set to `false`. Use the Config Builder to change a profile instead of changing only some values inside QuPath.

## Run in QuPath

1. Open the slide from the working folder.
2. Open `Automate`, then `Show script editor`.
3. Open `CoreAlign.groovy`.
4. Press `Run`.
5. Read the preflight dialog before continuing. It shows the open slide, working folder, exact config path, profile, grid, and core diameter.
6. Continue only when every value matches the physical TMA.

For the validated example, the first run must report:

```text
Grid: 18 rows x 7 columns
Core diameter: 0.6 mm
117 present
9 missing
Structural QC: passed
Technical detection validation: 126/126
```

If the result differs, do not approve it. Open `tma_grid_qc/*_grid_qc.png`, correct the config or selected slide, and run again.

## Human review and resume

The first valid run stops after detection. Inspect the live grid and QC image. Run the same `CoreAlign.groovy` file again to approve the exact reviewed grid and begin orientation.

CoreAlign saves an atomic checkpoint after each core. If QuPath stops, run the same file again. Completed cores are reused.

If a core needs correction:

- Use `TMA crop override <core name>` when the source region includes neighboring tissue.
- Use `Epidermis override <core name>` to point to the true epidermal side.
- Run the same file again. Only changed cores are recomputed.

## Output modes and expected time

`rotated_fullres` contains presentation PNG files. Each file is rotated first and cropped second at source pixel dimensions.

`rotated_multichannel_ome` contains UINT16 OME-TIFF files with every original channel. These are much slower and larger. In the validated test, one 2,606 by 2,606 core with 19 channels produced an approximately 99 MB OME-TIFF and required several minutes. Disable multichannel OME-TIFF in the Config Builder when only presentation PNG files are required.

## Safety gates

CoreAlign stops before approval or orientation when any of these checks fail:

- More than one CoreAlign config is beside the open slide
- Production config enables the advanced parameter dialog
- Detected positions are below the configured structural threshold
- Any complete row or column is empty when that is not allowed
- The validated example does not match its 126-position technical reference

Human review remains required. A correct software run does not establish autonomous clinical accuracy.
