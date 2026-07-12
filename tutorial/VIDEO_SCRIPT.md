# CoreAlign TMA — complete tutorial script

Target duration: 10–14 minutes. Delivery: calm, precise, friendly; bilingual on-screen labels with English narration and Thai subtitles.

## Chapters

1. **What CoreAlign solves** — manual per-core rotation, reproducibility, and the rotate-before-crop rule.
2. **Download from GitHub** — repository overview, Download ZIP, and release files.
3. **Prepare the folder** — slide, `CoreAlign.groovy`, and `corealign.config.json` together; never upload the slide to GitHub.
4. **Understand config** — array rows/columns, diameter, channel tokens, source-resolution export, and native OME storage.
5. **Open in QuPath** — open the OME-TIFF, open Script Editor, run `CoreAlign.groovy`.
6. **Review the grid** — QC image, correction and missing annotations, exact-hash approval.
7. **Per-core rotate then crop** — support window, connected epithelial component, post-rotation residual check, checkpoint.
8. **Human review** — `review.html`, contact sheet, crop override, epidermis override, selective resume.
9. **Outputs** — rotated full-resolution PNG for slides and native UINT16/19-channel OME-TIFF for archive.
10. **Presentation and limitations** — `QC_DRAFT` versus approved status; why autonomous 100% is not claimed.

## Narration

Welcome to CoreAlign TMA, a reproducible QuPath workflow for detecting tissue microarrays and orienting skin cores with the epidermis at the top. The key rule is simple: rotate first, crop second.

We begin on the public GitHub repository. Download the repository as a ZIP, then extract it locally. The repository contains one production runner named CoreAlign dot groovy, a readable JSON configuration, validation results, and bilingual documentation. Whole-slide images are deliberately excluded from GitHub.

Create a working folder and place three things together: your OME-TIFF slide, CoreAlign dot groovy, and corealign dot config dot JSON. In this tutorial we use the example slide from the Downloads folder.

Before opening QuPath, review the configuration. Rows and columns describe the array. Core diameter is the physical punch size. Nuclear channel tokens tell CoreAlign how to find DAPI-like channels. Epidermis channel tokens provide optional supporting markers. Export downsample one point zero preserves the source pixel dimensions. Native OME-TIFF export keeps every original channel but requires substantially more disk space.

Now open the slide in QuPath. Open the script editor, load CoreAlign dot groovy, and press Run. On the first pass, CoreAlign detects the grid and pauses. Inspect the grid QC carefully. Correct a misplaced core with a TMA correction annotation, and mark a truly empty position with TMA mark missing. Run the same file again and approve only the exact grid you inspected.

CoreAlign now processes one core at a time. For each core it refines the source region, estimates the epidermal direction, reads an oversized support window, rotates around the individual core center, checks the residual angle, and only then crops the final square. An atomic checkpoint is saved immediately, so an interruption never forces the whole array to restart.

When processing finishes, open the review page. It starts by showing only cores that need attention. If the crop includes a neighboring fragment, draw a TMA crop override rectangle. If the epidermis points in the wrong direction, add an Epidermis override point on the correct side. Run CoreAlign again. Only the changed core is recomputed; all valid checkpoints are reused.

The rotated full-resolution PNG is the visual output for contact sheets, figures, and slide presentations. The native OME-TIFF is a lossless, unresampled multichannel source crop for archival and quantitative follow-up. Keeping both outputs avoids confusing a presentation composite with the scientific source of truth.

CoreAlign does not claim autonomous one-hundred-percent biological accuracy. Instead, it makes every uncertainty visible and blocks final presentation status until the exact grid and orientation result hashes are approved. That is how the workflow reaches one-hundred-percent reviewed and accepted positions without hiding error.

You can now reuse the same workflow with another array by copying a profile and changing only the geometry and channel mapping. Thank you for using CoreAlign TMA.
