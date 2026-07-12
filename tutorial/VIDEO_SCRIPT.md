# CoreAlign TMA validated tutorial version 2

This version adds a mandatory preflight pause after the working-folder step. It corrects the failure mode in tutorial version 1, where a slide opened from Downloads could silently load a different config beside that slide.

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

The valid ElevenLabs narration from version 1 is retained. The outdated final sentence in narration chapter 3 is removed. A 15-second visual safety pause is inserted after chapter 3. The English and Thai subtitle tracks include the new safety instruction.
