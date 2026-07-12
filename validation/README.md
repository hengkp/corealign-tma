# Validation evidence

The current detector was validated on 12 July 2026 with QuPath 0.7.0 and `TMA_0.6mm_7_backsub.ome.tif`.

- The 18 by 7 grid contains 126 positions.
- CoreAlign classified 117 positions as present and 9 as missing.
- All rows and columns contain detected tissue.
- Presence or missing status agreed with the technical visual reference at 126 of 126 positions.
- A full-resolution single-core end-to-end test processed core `1-A` in the required order: source support window, rotation, then final crop.
- The rotated PNG retained source pixel dimensions. The OME-TIFF reopened in QuPath as 2,606 by 2,606 pixels, UINT16, with all 19 source channels.

This is technical workflow validation for one reference slide. It is not a claim of autonomous clinical accuracy. Grid placement, missing positions, crop region, and epidermal orientation still require human review before clinical or presentation use.
