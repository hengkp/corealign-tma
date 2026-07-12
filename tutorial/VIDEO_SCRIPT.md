# CoreAlign TMA tutorial version 3

This version keeps the validated preflight from version 2 and replaces the configuration chapter with the simplified Config Builder. The visible workflow is now choose the TMA, choose the output, and download.

## Non-negotiable preflight

Before pressing Run, confirm all of the following:

1. The slide, `CoreAlign.groovy`, and exactly one `corealign.config.json` are in one new working folder.
2. QuPath opened the slide copy from that folder.
3. For `TMA_0.6mm_7_backsub.ome.tif`, the preflight dialog shows profile `skin_18x7`, grid `18 x 7`, and core diameter `0.6 mm`.
4. Stop if the dialog shows another path, profile, grid, or diameter.

## Chapters

1. What CoreAlign solves
2. Download from GitHub
3. Prepare and verify the working folder
4. Configure your array
5. Open, run, and review the grid
6. Rotate first, crop second
7. Human review and selective resume
8. Presentation and archive outputs
9. Accuracy and approval policy
10. Reuse with other arrays

The valid ElevenLabs narration is retained. The outdated final sentence in narration chapter 3 remains removed. A 15-second visual safety pause follows chapter 3. The configuration chapter now shows the current lightweight builder and its optional advanced settings.
