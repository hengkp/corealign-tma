# Protocol สำหรับอ้างความถูกต้อง 99.9%

## 1. แยก metric ให้ชัด

ต้องรายงานอย่างน้อย 4 metric แยกกัน ห้ามรวมเป็นคำว่า accuracy ตัวเดียว:

1. presence precision/recall — core จริงกับ missing
2. row/column identity accuracy
3. center placement pass rate — เช่น center error ≤ 0.20 core diameter
4. orientation pass rate — pathologist ยืนยัน epidermis อยู่ upper band

ผลรวมแบบ end-to-end นับ pass เมื่อทั้ง 4 เงื่อนไขถูกต้องใน core เดียวกัน

## 2. Ground truth

- pathologists 2 คน label independently
- blind ต่อ algorithm output ในรอบแรก
- adjudicate disagreement
- ครอบคลุมหลาย staining batch, scanner, background, folded/fragmented/dim/missing tissue
- ห้ามใช้สไลด์ tuning เป็น test set

## 3. จำนวนตัวอย่าง

ที่ error target 0.1% หากพบ 0 error ต้องมีอย่างน้อยประมาณ 2,995 independent decisions
เพื่อให้ one-sided 95% upper error bound ตาม rule of three ต่ำกว่า 0.1%

สำหรับ TMA 126 positions เท่ากับอย่างน้อยประมาณ 24 slides ที่เป็น independent test set
และต้องไม่มี error ใน metric ที่ต้องการอ้าง 99.9% หากพบ error ต้องใช้ exact binomial
confidence interval และจำนวนตัวอย่างมากขึ้น

## 4. Acceptance gate ต่อ slide

- structural grid invariant ผ่านทั้งหมด
- ไม่มี duplicate/out-of-bounds/blank name
- missing ทุกตำแหน่งได้รับ human confirmation
- checkpoint ครบทุก cell และไม่มี processing/export error
- non-missing core ทุกตัวผ่าน final visual orientation review
- exact grid hash และ orientation-result hash ถูกบันทึกเป็น `approvalMode: human`

## 5. Production monitoring

- audit 100% red/yellow และสุ่ม green ต่อเนื่องจน validation ครบ
- หลัง validation ให้สุ่มอย่างน้อย 10% ต่อ slide และ review 100% เมื่อ batch/scanner/channel mapping เปลี่ยน
- report automation rate แยกจาก accuracy: สัดส่วน core ที่ไม่ต้อง override
- detector/algorithm version ใหม่ต้อง revalidate; human-approved checkpoint เดิมไม่ถูกลบทิ้ง

