# Automation Design and Validation

## Workflow ปัจจุบัน

```text
OME-TIFF
  -> DAPI/OpenCV primary detection
  -> PCA + nearest-neighbor automatic row/column inference
  -> confidence gate or validated-reference geometry lock
  -> row-wise lattice indexing
  -> coverage validation
  -> independent unblurred-DAPI rescue + recenter
  -> grid QC + targeted review queue
  -> human correction/approval (SHA-256 grid hash)
  -> per-core region refinement + neighboring-fragment QC
  -> connected epithelial-component PCA + independent marker fallbacks
  -> source support read -> rotate -> post-rotation QC -> final crop
  -> full-resolution lossless PNG + optional native UINT16 multichannel OME-TIFF
  -> atomic checkpoint per core
  -> contact sheet/HTML review
  -> selective Epidermis override + resume
  -> final human approval (orientation-result hash)
```

workflow ทั้งหมดถูก bundle อยู่ใน `CoreAlign.groovy` ไฟล์เดียว
ขั้น `01–06` เป็น embedded payload และไม่ถูกอ่านจาก disk ขณะใช้งานปกติ

## Fail-safe และ recovery

- detector ไม่รันซ้ำเมื่อมี current approved checkpoint
- ไม่ต้องกรอกจำนวน row หรือ column; ระบบรับเฉพาะ geometry ที่ confidence ผ่านเกณฑ์
- known reference ใช้ geometry ที่ validate แล้วโดยอัตโนมัติ
- detector version ใหม่ invalidate เฉพาะ non-human test checkpoint เก่า
- live grid ที่มี human correction ไม่ถูกเขียนทับอัตโนมัติ
- Step 2 ปฏิเสธ grid ที่ hash ไม่ตรงก่อนอ่านภาพ
- output folder ผูกกับ grid hash จึงไม่ทับผลจากคนละ grid
- checkpoint เขียนแบบ atomic หลังแต่ละ core
- processing error ของ core หนึ่งไม่หยุด batch และ core นั้นถูก retry รอบถัดไป
- partial CSV อัปเดตทุก core
- output directory ผูกกับ grid hash + algorithm/profile/export identity จึงไม่ reuse ภาพ
  จาก config หรือ algorithm คนละรุ่น
- `TMA crop override` และ `Epidermis override` เป็นส่วนหนึ่งของ per-core signature;
  แก้ core เดียวแล้ว resume core อื่นได้
- test approval แยกจาก human approval

## Technical validation บนสไลด์นี้

- detector เดิม: 105 present / 21 missing และมี false-negative ชัดเจน
- detector ปรับปรุง: 117 present / 9 missing
- slide-specific technical reference: 126/126 presence/missing classifications ถูกต้อง
  (false-present 0, false-missing 0)
- rescue ใช้ independent unblurred nuclear support centroid; ไม่ใช้ merged blur เป็น presence truth
- grid structural errors: 0
- grid targeted review queue: 25
- orientation first run: 126 processed
- immediate resume: 126 reused / 0 processed
- one override: 125 reused / 1 processed
- moved core 3 px: approval hash mismatch และถูก block ก่อน processing
- QuPath GUI: runner รุ่นล่าสุดแสดง 117 present / 9 missing / 25 queued ใน approval dialog
- isolated one-file test โดยไม่มี companion scripts: end-to-end และ resume ผ่าน
- rotate-then-crop visual test: 1-A, 4-A, 3-C, 4-C, 13-D และ 18-D มี epidermis ด้านบน
- known neighboring-fragment case 4-C ถูกส่ง `region_review` โดย multiple-region QC
- source-resolution export test: PNG 2,606 × 2,606; OME-TIFF UINT16, 19 channels
- full-array v3.6 fast validation: 126 processed, 0 processing error, 77 auto-pass,
  40 human-review, 9 missing; presentation remained `QC_DRAFT`

## ข้อจำกัด

confidence color เป็น triage score ไม่ใช่ calibrated probability และไม่สามารถใช้พิสูจน์
99.9% ได้ ผลปัจจุบันยังเป็น one-slide technical validation สำหรับ research/QC/presentation
จนกว่าจะผ่าน protocol ใน `ACCURACY_99_9_PROTOCOL.md`
