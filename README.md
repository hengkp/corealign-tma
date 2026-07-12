# CoreAlign TMA

> Rotate first. Crop second. Review what matters.  
> หมุนก่อน แล้วจึงครอป ตรวจเฉพาะจุดที่จำเป็น

CoreAlign TMA is a configurable, resumable QuPath workflow for detecting TMA grids, orienting individual skin cores with epidermis at the top, and exporting presentation-ready PNG plus source-quality multichannel OME-TIFF crops.

CoreAlign TMA คือ QuPath workflow ที่ตั้งค่าได้และ resume ได้ สำหรับตรวจหา TMA grid หมุนชิ้นเนื้อผิวหนังทีละ core ให้ epidermis อยู่ด้านบน และส่งออกทั้ง PNG สำหรับงานนำเสนอและ OME-TIFF ครบทุก channel

## Highlights / จุดเด่น

- One production runner: `workflow/CoreAlign.groovy`
- Per-core order: refine → orient → rotate support window → QC → crop
- Atomic checkpoint after every core; failed or corrected cores resume selectively
- Human gates for grid, missing calls, contaminated regions, orientation, and presentation
- Full-resolution rotated RGB PNG and rotated UINT16 multichannel OME-TIFF
- Profiles for other arrays, stains, scanners, and experiments
- Bilingual documentation with light/dark mode

## Requirements / สิ่งที่ต้องมี

- QuPath 0.7.x
- A local whole-slide image supported by QuPath; OME-TIFF is recommended
- Enough disk space: native 19-channel crops from the reference array used about 90 MB/core

## Quick start / เริ่มต้นใช้งาน

1. Download this repository as ZIP and extract it.
2. Copy `workflow/CoreAlign.groovy` and `workflow/corealign.config.json` beside your slide.
3. Open the slide in QuPath.
4. Open **Automate → Show script editor**, open `CoreAlign.groovy`, and press **Run**.
5. First run: inspect and correct the detected grid, then run the same file again.
6. Second run: approve the grid; CoreAlign processes cores one by one and pauses for orientation review.
7. Add `TMA crop override` or `Epidermis override` annotations only where required, then run again.
8. Final approval creates the presentation package. A package with unresolved items remains `QC_DRAFT`.

ภาษาไทย: ดาวน์โหลด ZIP → วางไฟล์ Groovy และ config ข้างสไลด์ → เปิดสไลด์ใน QuPath → รันไฟล์เดิมซ้ำตาม human gate ระบบจะไม่ประมวลผล core ที่เสร็จแล้วใหม่

## Configuration / การตั้งค่า

Edit `corealign.config.json`. The most important values are:

For the easiest setup, open the **Visual Config Builder** on the documentation website. It provides presets, validation, a live JSON preview, Thai/English labels, and downloads a ready-to-use `corealign.config.json` entirely in the browser without uploading slide data.

วิธีที่ง่ายที่สุดคือใช้หน้า **Visual Config Builder** บนเว็บไซต์ เลือก preset กรอก geometry และ channel mapping ตรวจ JSON แบบทันที แล้วกดดาวน์โหลดไฟล์พร้อมใช้งาน โดยไม่มีการอัปโหลดข้อมูลสไลด์

| Key | Meaning / ความหมาย |
|---|---|
| `grid.rows`, `grid.columns` | Array dimensions / จำนวนแถวและคอลัมน์ |
| `grid.coreDiameterMM` | Punch diameter / เส้นผ่านศูนย์กลาง core |
| `nuclearChannelTokens` | Words used to find DAPI-like channels |
| `epidermisChannelTokens` | Optional markers that support skin orientation |
| `exportDownsample: 1.0` | Preserve source pixel dimensions |
| `saveFullResolutionPng` | Save rotated lossless RGB PNG |
| `saveRotatedMultichannelOmeTiff` | Bake the accepted rotation into every original channel |
| `saveNativeOmeTiff` | Optionally keep an additional unrotated source archive |
| `postRotationToleranceDeg` | Residual angle that triggers review |

For a new experiment, duplicate a profile, change `activeProfile`, and update only the array geometry and channel tokens first. Never copy slide-specific missing positions into a new profile.

## Human overrides / การแก้เฉพาะ core

- `TMA correction 4-C`: small annotation at the correct core center
- `TMA mark missing 14-G`: annotation near a truly empty position
- `TMA crop override 4-C`: rectangle defining the source region to rotate and crop
- `Epidermis override 4-C`: point placed on the epidermal side

Overrides are included in each core signature. Rerunning invalidates only the affected checkpoint.

## Outputs

```text
tma_auto_orient_export/<image>_grid_<hash>_orient_<run-id>/
├── rotated_previews/
├── rotated_fullres/       # rotated, then cropped RGB PNG
├── rotated_multichannel_ome/ # rotated, then cropped UINT16 all-channel OME-TIFF
├── source_native_ome/     # optional unrotated source archive
├── checkpoints/
├── orientation_results.csv
├── orientation_review_queue.csv
├── orientation_contact_sheet.png
├── review.html
└── run_manifest.json
```

The rotated multichannel OME-TIFF uses the same accepted per-core transform for all original channels. Rotation requires interpolation; keep the original whole slide as the immutable source of truth. `source_native_ome` can be enabled when an additional unrotated per-core archive is useful.

## Validation and accuracy

Reference-slide technical validation processed 126/126 positions with zero processing errors: 117 present, 9 missing, 77 automatic passes, and 40 routed to human review. This does **not** prove autonomous 100% biological accuracy on unseen slides.

“100%” in this workflow means every position is accepted after human review and exact grid/result hashes are approved. See [`docs/ACCURACY_99_9_PROTOCOL.md`](docs/ACCURACY_99_9_PROTOCOL.md).

## Documentation and tutorial

- Documentation website: [corealign-tma.heng-kkpk.chatgpt.site](https://corealign-tma.heng-kkpk.chatgpt.site)
- Visual Config Builder: [open builder](https://corealign-tma.heng-kkpk.chatgpt.site/config-builder)
- Video tutorial: available from GitHub Releases after production
- Validation artifacts: [`validation/`](validation/)

## Safety

Research/QC software only. Independent pathologist review and local validation are required before clinical claims. Do not publish patient-identifying slide data or private annotations.

## Credits

See [CREDITS.md](CREDITS.md). CoreAlign uses Remix Icon and draws design-process inspiration from the resources credited there. No Flaticon or Lottie asset is redistributed without an explicit compatible license.
