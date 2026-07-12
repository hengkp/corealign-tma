"use client";

import { useEffect, useState } from "react";

const repo = "https://github.com/hengkp/corealign-tma";

const copy = {
  en: {
    skip: "Skip to content",
    nav: ["Overview", "Workflow", "Configuration", "Human review", "Outputs", "Tutorial"],
    eyebrow: "Open, reproducible QuPath workflow",
    title: "Rotate first. Crop second.",
    lead: "CoreAlign TMA detects an array, focuses on every individual core, places skin epidermis at the top, and exports presentation-ready images without hiding uncertain cases.",
    download: "Download from GitHub",
    start: "Start in 5 minutes",
    proof: ["126/126 positions processed", "117 present · 9 missing", "0 processing errors", "77 auto-pass · 40 reviewed"],
    overviewTitle: "One file. Every checkpoint preserved.",
    overviewBody: "Run CoreAlign.groovy in QuPath. The embedded state machine detects, pauses for grid approval, rotates each core from a safe source window, crops after rotation, and resumes exactly where it stopped.",
    steps: [
      ["Download", "Download the repository ZIP. Place CoreAlign.groovy and corealign.config.json beside your OME-TIFF."],
      ["Detect & approve", "Open the slide in QuPath, run the script, inspect the grid QC, correct missing or misplaced cores, then run again."],
      ["Rotate → crop", "Each present core is refined, oriented, rotated from an oversized support region, checked, and only then cropped."],
      ["Review & resume", "Review only yellow/red cores. Add a crop or epidermis override and rerun; completed cores are reused."],
      ["Present", "Use rotated PNG panels for slides. Keep native multichannel OME-TIFF crops as the source-quality archive."],
    ],
    configTitle: "Configuration in plain language",
    configBody: "Start with the supplied skin 18×7 profile, then change only the values that describe your array and channels.",
    configRows: [
      ["grid.rows / columns", "Number of array positions", "18 / 7"],
      ["coreDiameterMM", "Physical punch diameter", "0.6"],
      ["nuclearChannelTokens", "Words used to find DAPI-like channels", "dapi, hoechst"],
      ["epidermisChannelTokens", "Helpful epithelial/skin markers", "keratin, panck…"],
      ["exportDownsample", "1.0 keeps source pixel dimensions", "1.0"],
      ["saveNativeOmeTiff", "Archive all original channels", "true"],
      ["postRotationToleranceDeg", "Residual angle before review", "12"],
    ],
    reviewTitle: "100% accepted—not 100% guessed",
    reviewBody: "A confidence score is triage, not a clinical probability. CoreAlign blocks final presentation status until grid calls, ambiguous regions, and orientation results are reviewed against exact hashes.",
    overrides: [
      ["TMA correction", "Place a small annotation at the correct core center and include the cell name."],
      ["TMA crop override", "Draw the desired source rectangle for a contaminated or off-center core."],
      ["Epidermis override", "Place a point on the epidermal side when automatic orientation is wrong."],
    ],
    outputsTitle: "Two outputs, two purposes",
    outputs: [
      ["Rotated full-resolution PNG", "Source-pixel-size RGB composite. Best for contact sheets, figures, and slide presentations."],
      ["Native OME-TIFF", "Lossless UINT16 multichannel source crop. Best for archival and quantitative follow-up."],
    ],
    tutorialTitle: "Complete video tutorial",
    tutorialBody: "Follow the full journey: GitHub download, file preparation, QuPath grid review, per-core orientation, override, resume, and export.",
    watch: "Watch tutorial",
    read: "Read the bilingual README",
    cite: "Design & asset credits",
    footer: "Research/QC software. Pathologist review is required before clinical claims.",
  },
  th: {
    skip: "ข้ามไปยังเนื้อหา",
    nav: ["ภาพรวม", "ขั้นตอน", "การตั้งค่า", "การตรวจโดยคน", "ผลลัพธ์", "วิดีโอ"],
    eyebrow: "QuPath workflow แบบเปิดและทำซ้ำได้",
    title: "หมุนก่อน แล้วจึงครอป",
    lead: "CoreAlign TMA ตรวจหา array, โฟกัสทีละ core, หมุนให้ epidermis ของผิวหนังอยู่ด้านบน และส่งออกภาพพร้อมนำเสนอ โดยไม่ซ่อนกรณีที่ไม่แน่นอน",
    download: "ดาวน์โหลดจาก GitHub",
    start: "เริ่มใช้งานใน 5 นาที",
    proof: ["ประมวลผล 126/126 ตำแหน่ง", "มีชิ้นเนื้อ 117 · missing 9", "processing error 0", "ผ่านอัตโนมัติ 77 · ตรวจโดยคน 40"],
    overviewTitle: "ไฟล์เดียว และเก็บ checkpoint ทุกขั้น",
    overviewBody: "รัน CoreAlign.groovy ใน QuPath ระบบจะ detect, หยุดให้ตรวจ grid, อ่านบริเวณต้นฉบับที่ใหญ่พอ, หมุนทีละ core, ครอปหลังหมุน และ resume ต่อจากจุดเดิมได้",
    steps: [
      ["ดาวน์โหลด", "ดาวน์โหลด ZIP จาก repository แล้ววาง CoreAlign.groovy และ corealign.config.json ไว้ข้างไฟล์ OME-TIFF"],
      ["Detect และอนุมัติ", "เปิดสไลด์ใน QuPath รัน script ตรวจ grid QC แก้ core ที่ผิดหรือ missing แล้วรันอีกครั้ง"],
      ["หมุน → ครอป", "แต่ละ core จะถูก refine, หาแนว epidermis, หมุนจาก support region ที่ใหญ่พอ ตรวจซ้ำ แล้วจึงครอป"],
      ["ตรวจและ resume", "ตรวจเฉพาะสีเหลือง/แดง เพิ่ม crop หรือ epidermis override แล้วรันซ้ำ ระบบ reuse core ที่เสร็จแล้ว"],
      ["นำเสนอ", "ใช้ rotated PNG ทำสไลด์ และเก็บ native multichannel OME-TIFF เป็น archive คุณภาพต้นฉบับ"],
    ],
    configTitle: "ตั้งค่าด้วยภาษาที่เข้าใจง่าย",
    configBody: "เริ่มจาก profile skin 18×7 ที่ให้มา แล้วแก้เฉพาะค่าที่บอกลักษณะ array และชื่อ channel ของคุณ",
    configRows: [
      ["grid.rows / columns", "จำนวนตำแหน่งใน array", "18 / 7"],
      ["coreDiameterMM", "เส้นผ่านศูนย์กลาง punch", "0.6"],
      ["nuclearChannelTokens", "คำที่ใช้ค้นหา DAPI-like channels", "dapi, hoechst"],
      ["epidermisChannelTokens", "marker ที่ช่วยบอกชั้นผิว", "keratin, panck…"],
      ["exportDownsample", "1.0 รักษาขนาดพิกเซลต้นฉบับ", "1.0"],
      ["saveNativeOmeTiff", "เก็บทุก channel ต้นฉบับ", "true"],
      ["postRotationToleranceDeg", "มุมคลาดเคลื่อนก่อนส่ง review", "12"],
    ],
    reviewTitle: "ครบ 100% หลังตรวจ ไม่ใช่เดา 100%",
    reviewBody: "confidence score ใช้คัดลำดับ ไม่ใช่ความน่าจะเป็นทางคลินิก ระบบจะไม่ให้สถานะพร้อมนำเสนอจนกว่าจะตรวจ grid, region และ orientation ที่กำกวมพร้อม exact hash",
    overrides: [
      ["TMA correction", "วาง annotation เล็กที่จุดกึ่งกลาง core ที่ถูกต้องและใส่ชื่อ cell"],
      ["TMA crop override", "วาด rectangle บริเวณต้นฉบับที่ต้องการเมื่อมีเศษข้างเคียงหรือ core ไม่อยู่กึ่งกลาง"],
      ["Epidermis override", "วางจุดบนด้าน epidermis เมื่อระบบหมุนผิด"],
    ],
    outputsTitle: "ผลลัพธ์สองชนิด ใช้ต่างวัตถุประสงค์",
    outputs: [
      ["Rotated full-resolution PNG", "RGB composite ที่ขนาดพิกเซลต้นฉบับ เหมาะกับ contact sheet, figure และ presentation"],
      ["Native OME-TIFF", "source crop แบบ lossless UINT16 ครบทุก channel เหมาะกับ archive และวิเคราะห์ต่อ"],
    ],
    tutorialTitle: "วิดีโอสอนฉบับสมบูรณ์",
    tutorialBody: "ทำตามตั้งแต่ดาวน์โหลด GitHub เตรียมไฟล์ ตรวจ grid ใน QuPath หมุนทีละ core แก้ override, resume และ export",
    watch: "ชมวิดีโอ",
    read: "อ่าน README สองภาษา",
    cite: "เครดิตงานออกแบบและ asset",
    footer: "ซอฟต์แวร์สำหรับงานวิจัย/QC ต้องผ่านการตรวจโดย pathologist ก่อนกล่าวอ้างทางคลินิก",
  },
} as const;

export default function Home() {
  const [lang, setLang] = useState<"en" | "th">("en");
  const [dark, setDark] = useState(false);
  const t = copy[lang];

  useEffect(() => {
    const saved = localStorage.getItem("corealign-theme");
    const next = saved ? saved === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
    setDark(next);
    document.documentElement.dataset.theme = next ? "dark" : "light";
  }, []);

  function toggleTheme() {
    const next = !dark;
    setDark(next);
    localStorage.setItem("corealign-theme", next ? "dark" : "light");
    document.documentElement.dataset.theme = next ? "dark" : "light";
  }

  return (
    <>
      <a className="skip" href="#content">{t.skip}</a>
      <header className="topbar">
        <a className="brand" href="#overview" aria-label="CoreAlign TMA home">
          <span className="brandMark"><i className="ri-focus-3-line" /></span>
          <span>CoreAlign <b>TMA</b></span>
        </a>
        <nav aria-label="Primary navigation">
          {t.nav.map((n, i) => <a key={n} href={`#${["overview", "workflow", "config", "review", "outputs", "tutorial"][i]}`}>{n}</a>)}
        </nav>
        <div className="controls">
          <button onClick={() => setLang(lang === "en" ? "th" : "en")} aria-label="Switch language"><i className="ri-translate-2" /> {lang === "en" ? "ไทย" : "EN"}</button>
          <button className="iconButton" onClick={toggleTheme} aria-label="Toggle color theme"><i className={dark ? "ri-sun-line" : "ri-moon-line"} /></button>
        </div>
      </header>

      <main id="content">
        <section className="hero" id="overview">
          <div className="heroCopy">
            <p className="eyebrow"><span />{t.eyebrow}</p>
            <h1>{t.title}</h1>
            <p className="lead">{t.lead}</p>
            <div className="actions">
              <a className="primary" href={`${repo}/archive/refs/heads/main.zip`}><i className="ri-download-cloud-2-line" />{t.download}</a>
              <a className="secondary" href="#workflow"><i className="ri-play-circle-line" />{t.start}</a>
            </div>
            <div className="proof">{t.proof.map((p, i) => <div key={p}><strong>{p.split(" ")[0]}</strong><span>{p.substring(p.indexOf(" ") + 1)}</span><i className={["ri-layout-grid-line", "ri-checkbox-circle-line", "ri-shield-check-line", "ri-user-follow-line"][i]} /></div>)}</div>
          </div>
          <div className="heroVisual" aria-label="Rotated skin TMA core example">
            <div className="orbit"><span /><span /><span /></div>
            <img src="/images/core-1a-rotated.png" alt="Skin TMA core rotated with epidermis at the top" />
            <div className="topFlag"><i className="ri-arrow-up-line" /> EPIDERMIS</div>
          </div>
        </section>

        <section className="statement">
          <p className="sectionIndex">01 / OVERVIEW</p>
          <h2>{t.overviewTitle}</h2>
          <p>{t.overviewBody}</p>
        </section>

        <section className="section" id="workflow">
          <div className="sectionHead"><p className="sectionIndex">02 / WORKFLOW</p><h2>{t.nav[1]}</h2></div>
          <div className="steps">{t.steps.map((s, i) => <article key={s[0]}><span>{String(i + 1).padStart(2, "0")}</span><i className={["ri-github-line", "ri-scan-2-line", "ri-refresh-line", "ri-user-settings-line", "ri-slideshow-3-line"][i]} /><h3>{s[0]}</h3><p>{s[1]}</p></article>)}</div>
        </section>

        <section className="section configSection" id="config">
          <div className="sectionHead"><p className="sectionIndex">03 / CONFIG</p><h2>{t.configTitle}</h2><p>{t.configBody}</p></div>
          <div className="configGrid">
            <div className="codeCard"><div><span /><span /><span /><b>corealign.config.json</b></div><pre>{`{
  "grid": {
    "rows": 18,
    "columns": 7,
    "coreDiameterMM": 0.6
  },
  "orientation": {
    "exportDownsample": 1.0,
    "saveNativeOmeTiff": true,
    "postRotationToleranceDeg": 12
  }
}`}</pre><a className="builderLaunch" href="/config-builder"><i className="ri-dashboard-3-line" /> Open visual Config Builder · เปิดหน้าสร้าง Config</a></div>
            <div className="configTable" role="table">{t.configRows.map(r => <div role="row" key={r[0]}><code>{r[0]}</code><span>{r[1]}</span><b>{r[2]}</b></div>)}</div>
          </div>
        </section>

        <section className="section reviewSection" id="review">
          <div className="reviewCopy"><p className="sectionIndex">04 / HUMAN IN THE LOOP</p><h2>{t.reviewTitle}</h2><p>{t.reviewBody}</p>{t.overrides.map(o => <div className="override" key={o[0]}><i className="ri-edit-box-line" /><div><h3>{o[0]}</h3><p>{o[1]}</p></div></div>)}</div>
          <figure><img src="/images/contact-sheet.jpg" alt="Full-array orientation contact sheet" /><figcaption>v3.6 full-array validation · 126 positions · review queue preserved</figcaption></figure>
        </section>

        <section className="section" id="outputs">
          <div className="sectionHead"><p className="sectionIndex">05 / OUTPUTS</p><h2>{t.outputsTitle}</h2></div>
          <div className="outputCards">{t.outputs.map((o, i) => <article key={o[0]}><i className={i ? "ri-stack-line" : "ri-image-2-line"} /><div><p>{i ? "ARCHIVE" : "PRESENTATION"}</p><h3>{o[0]}</h3><span>{o[1]}</span></div></article>)}</div>
        </section>

        <section className="tutorial" id="tutorial">
          <div><p className="sectionIndex">06 / TUTORIAL</p><h2>{t.tutorialTitle}</h2><p>{t.tutorialBody}</p><div className="actions"><a className="primary" href={`${repo}/releases`}><i className="ri-movie-2-line" />{t.watch}</a><a className="secondary" href={`${repo}#readme`}><i className="ri-book-open-line" />{t.read}</a></div></div>
          <div className="videoFrame"><i className="ri-play-large-fill" /><span>CoreAlign TMA · Complete workflow</span></div>
        </section>
      </main>

      <footer><div className="brand"><span className="brandMark"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></div><p>{t.footer}</p><div><a href={`${repo}/blob/main/CREDITS.md`}>{t.cite}</a><a href={repo}><i className="ri-github-fill" /> GitHub</a></div></footer>
    </>
  );
}
