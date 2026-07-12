# Rotate-then-crop validation — 2026-07-11

## Implementation under test

- QuPath 0.7.0 arm64
- one-file runner: `00_run_skin_tma_auto_pipeline.groovy`
- orientation algorithm: `skin-epidermis-orient-3.6-component-region-qc`
- reference image: `TMA_0.6mm_7_backsub.ome.tif` (30,801 × 59,801, 19 channels)
- approved grid hash: `8efe041f08d4799a153b11e41a2d2a36622e35cc4f1dd4e1c203150059639eb5`

## Verified processing order

For every non-missing core the runner performs:

1. refine/override the source center and core region;
2. estimate the epidermal direction from the individual core;
3. read a support square at least large enough for the rotated final square;
4. rotate the support image around the individual core center;
5. run post-rotation residual orientation QC and correct once when safe;
6. crop the final square only after rotation;
7. atomically save the per-core checkpoint.

This order is recorded as `rotation_then_crop=true` in `orientation_results.csv` and
`rotationThenCrop=true` in `run_manifest.json`.

## Tests performed

| Test | Result |
|---|---|
| Restore approved grid | 126/126 technical presence/missing agreement |
| Connected-component direction, core 1-A | epidermis moved from bottom to top; residual 0.1° |
| Diverse visual sample | 1-A, 4-A, 3-C, 4-C, 13-D, 18-D moved epidermis to upper side |
| Neighboring fragment QC | 4-C marked `region_review`; not silently auto-accepted |
| Resume/checkpoints | partial single-core runs completed without reprocessing unrelated cores |
| Source-resolution rotated RGB | lossless PNG 2,606 × 2,606 at downsample 1.0 |
| Native source archive | lossless OME-TIFF 2,606 × 2,606, UINT16, 19 named channels |
| Full-array fast validation | 126 processed, 0 processing error, 117 present, 9 missing |
| Automated triage | 77 auto-pass; 40 routed to human review |
| Presentation selection | 1/48 selected slots remained review; package correctly stayed `QC_DRAFT` |

## Interpretation of “100%”

The runner can enforce **100% reviewed/accepted positions** by blocking final approval until
all present cores and all missing calls are confirmed and the exact hashes are approved.
It cannot honestly guarantee 100% autonomous biological correctness on unseen slides.
Confidence is a triage score, not a probability. Ambiguous regions are routed to the human
queue and corrected with `TMA crop override` and/or `Epidermis override`; the next run
recomputes only those cores.

## Rotated multichannel export — v3.7

Core 1-A was exported at `exportDownsample: 1.0` after the accepted rotation was
applied independently to every source plane. The resulting OME-TIFF was reopened in
QuPath and verified as 2,606 × 2,606 pixels, UINT16, 19 channels, with all original
channel names preserved. The matching rotated RGB PNG was also 2,606 × 2,606 pixels.

Geometric rotation requires interpolation. The original whole-slide OME-TIFF remains
the immutable quantitative source of truth; the rotated multichannel crop is a
registered derivative with its transform recorded in `orientation_results.csv`.
