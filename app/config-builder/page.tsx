"use client";

import { useMemo, useState } from "react";

type FormState = {
  profile: string;
  description: string;
  rows: number;
  columns: number;
  diameter: number;
  nuclear: string;
  epidermis: string;
  analysisDownsample: number;
  exportDownsample: number;
  savePng: boolean;
  saveOme: boolean;
  residual: number;
  gridApproval: boolean;
  orientationApproval: boolean;
};

const presets: Record<string, Partial<FormState>> = {
  "Skin 18 × 7": { profile: "skin_18x7", description: "0.6 mm skin CyCIF TMA", rows: 18, columns: 7, diameter: 0.6, nuclear: "dapi, hoechst, nuclear", epidermis: "keratin, cytokeratin, panck, epcam" },
  "Skin 12 × 8": { profile: "skin_12x8", description: "Configurable skin TMA", rows: 12, columns: 8, diameter: 1.0, nuclear: "dapi, hoechst, nuclear", epidermis: "keratin, panck, epcam" },
  "Generic TMA": { profile: "generic_tma", description: "Generic peripheral-tissue orientation", rows: 10, columns: 10, diameter: 1.0, nuclear: "dapi, hoechst, nuclear", epidermis: "keratin, panck, epcam" },
};

const initial: FormState = {
  profile: "skin_18x7", description: "0.6 mm skin CyCIF TMA", rows: 18, columns: 7,
  diameter: 0.6, nuclear: "dapi, hoechst, nuclear", epidermis: "keratin, cytokeratin, panck, epcam",
  analysisDownsample: 4, exportDownsample: 1, savePng: true, saveOme: true,
  residual: 12, gridApproval: true, orientationApproval: true,
};

function tokens(value: string) { return value.split(",").map(v => v.trim().toLowerCase()).filter(Boolean); }

function makeConfig(f: FormState) {
  return {
    schemaVersion: 1,
    activeProfile: f.profile,
    profiles: {
      [f.profile]: {
        description: f.description,
        grid: {
          rows: f.rows, columns: f.columns, coreDiameterMM: f.diameter,
          cropPaddingFactor: 1.75, rowScheme: "1, 2, 3...", columnScheme: "A, B, C...",
          showAdvancedDialog: true, useExistingGridUnlessRectangleSelected: true,
          trustNondefaultExistingGrid: true,
        },
        detection: {
          algorithmVersion: "skin-tma-detect-2.2-nuclear-rescue", channelMode: "nuclear",
          customChannels: "", downsample: 8, blurSigmaFraction: 0.25,
          otsuThresholdScale: 0.7, minBlobAreaFraction: 0.05,
          maxBlobAreaFraction: 5, minAssignedFractionToBuildGrid: 0.3,
          maxMissingFractionToPreserve: 0.06,
        },
        orientation: {
          algorithmVersion: "skin-epidermis-orient-3.6-component-region-qc",
          analysisDownsample: f.analysisDownsample, exportDownsample: f.exportDownsample,
          previewMaxPixels: 900, cropScale: 1.05, rotationSupportScale: 1.45,
          regionRefinementEnabled: true, regionSearchScale: 1.55,
          regionMaxCenterShiftFraction: 0.3, regionTissueMargin: 1.12,
          regionMaxCropScale: 1.15, regionReviewConfidence: 0.12,
          saveFullResolutionPng: f.savePng, saveNativeOmeTiff: f.saveOme,
          cropOverrideClassName: "TMA crop override", postRotationToleranceDeg: f.residual,
          postRotationMaxIterations: 2, angularSectors: 72, outerRingInner: 0.42,
          outerRingOuter: 1.02, tissueThresholdScale: 0.55,
          reviewConfidence: 0.12, okConfidence: 0.28,
          nuclearChannelTokens: tokens(f.nuclear), epidermisChannelTokens: tokens(f.epidermis),
          rgbRedChannelTokens: tokens(f.epidermis), rgbGreenChannelTokens: [],
          overrideClassName: "Epidermis override",
        },
        quality: {
          requireHumanGridApproval: f.gridApproval,
          requireHumanOrientationApproval: f.orientationApproval,
          blockPresentationWhenAnySelectedCoreNeedsReview: true,
        },
        presentation: { enabled: false, title: "TMA presentation", conditions: [], treatmentColumns: [], comparisons: [] },
      },
    },
  };
}

export default function ConfigBuilder() {
  const [f, setF] = useState<FormState>(initial);
  const [lang, setLang] = useState<"en" | "th">("en");
  const config = useMemo(() => makeConfig(f), [f]);
  const json = useMemo(() => JSON.stringify(config, null, 2), [config]);
  const errors = [
    ...(f.rows < 1 || f.rows > 100 ? ["Rows must be between 1 and 100"] : []),
    ...(f.columns < 1 || f.columns > 100 ? ["Columns must be between 1 and 100"] : []),
    ...(f.diameter <= 0 ? ["Core diameter must be positive"] : []),
    ...(tokens(f.nuclear).length === 0 ? ["Add at least one nuclear channel token"] : []),
    ...(f.exportDownsample < 1 ? ["Export downsample must be at least 1.0"] : []),
  ];

  const labels = lang === "en" ? {
    title: "Config Builder", sub: "Create a validated CoreAlign profile without editing JSON by hand.",
    profile: "Profile", geometry: "Array geometry", channels: "Channel mapping", output: "Output & QC",
    preview: "Live configuration", download: "Download corealign.config.json", valid: "Ready to download",
    invalid: "Fix required fields", back: "Documentation", preset: "Start from preset",
  } : {
    title: "ตัวช่วยสร้าง Config", sub: "สร้าง CoreAlign profile ที่ตรวจสอบแล้วโดยไม่ต้องแก้ JSON ด้วยตนเอง",
    profile: "โปรไฟล์", geometry: "รูปแบบ Array", channels: "การจับคู่ Channel", output: "ผลลัพธ์และ QC",
    preview: "ตัวอย่าง Config แบบทันที", download: "ดาวน์โหลด corealign.config.json", valid: "พร้อมดาวน์โหลด",
    invalid: "กรุณาแก้ค่าที่แจ้ง", back: "กลับหน้า Documentation", preset: "เริ่มจาก Preset",
  };

  function set<K extends keyof FormState>(key: K, value: FormState[K]) { setF(v => ({ ...v, [key]: value })); }
  function download() {
    if (errors.length) return;
    const blob = new Blob([json + "\n"], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a"); a.href = url; a.download = "corealign.config.json"; a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main className="builderShell">
      <aside className="builderSide">
        <a className="brand" href="/"><span className="brandMark"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <div className="builderNav">
          {[labels.profile, labels.geometry, labels.channels, labels.output].map((x, i) => <a key={x} href={`#builder-${i}`}><span>{i + 1}</span><i className={["ri-profile-line", "ri-layout-grid-line", "ri-contrast-drop-2-line", "ri-shield-check-line"][i]} />{x}</a>)}
        </div>
        <a className="builderBack" href="/"><i className="ri-arrow-left-line" />{labels.back}</a>
      </aside>

      <div className="builderMain">
        <header className="builderTop">
          <div><p className="sectionIndex">COREALIGN CONTROL PLANE</p><h1>{labels.title}</h1><span>{labels.sub}</span></div>
          <div className={`builderStatus ${errors.length ? "bad" : "good"}`}><i className={errors.length ? "ri-error-warning-line" : "ri-checkbox-circle-line"} /><div><b>{errors.length ? labels.invalid : labels.valid}</b><small>{f.rows * f.columns} positions · {f.exportDownsample === 1 ? "source resolution" : `${f.exportDownsample}× downsample`}</small></div></div>
          <button className="builderLang" onClick={() => setLang(lang === "en" ? "th" : "en")}><i className="ri-translate-2" />{lang === "en" ? "ไทย" : "EN"}</button>
        </header>

        <div className="builderWorkspace">
          <div className="builderForm">
            <section className="builderCard presetCard"><div className="cardTitle"><i className="ri-magic-line" /><div><h2>{labels.preset}</h2><p>Validated starting points</p></div></div><div className="presetButtons">{Object.entries(presets).map(([name, value]) => <button key={name} onClick={() => setF(v => ({ ...v, ...value }))}>{name}<i className="ri-arrow-right-line" /></button>)}</div></section>

            <section className="builderCard" id="builder-0"><div className="cardTitle"><span>01</span><div><h2>{labels.profile}</h2><p>Identity and reusable description</p></div></div><div className="fieldGrid"><label>Profile ID<input value={f.profile} onChange={e => set("profile", e.target.value.replace(/[^a-zA-Z0-9_-]/g, "_"))} /></label><label>Description<input value={f.description} onChange={e => set("description", e.target.value)} /></label></div></section>

            <section className="builderCard" id="builder-1"><div className="cardTitle"><span>02</span><div><h2>{labels.geometry}</h2><p>Describe physical TMA layout</p></div></div><div className="fieldGrid three"><label>Rows<input type="number" min="1" max="100" value={f.rows} onChange={e => set("rows", +e.target.value)} /></label><label>Columns<input type="number" min="1" max="100" value={f.columns} onChange={e => set("columns", +e.target.value)} /></label><label>Core diameter (mm)<input type="number" min="0.1" step="0.1" value={f.diameter} onChange={e => set("diameter", +e.target.value)} /></label></div><div className="arrayPreview" style={{gridTemplateColumns:`repeat(${Math.min(f.columns,12)},1fr)`}}>{Array.from({length:Math.min(f.rows*f.columns,120)},(_,i)=><span key={i} />)}</div></section>

            <section className="builderCard" id="builder-2"><div className="cardTitle"><span>03</span><div><h2>{labels.channels}</h2><p>Comma-separated names; matching is case-insensitive</p></div></div><div className="fieldGrid"><label>Nuclear channel tokens<textarea value={f.nuclear} onChange={e => set("nuclear",e.target.value)} /><small>DAPI, Hoechst, nuclear…</small></label><label>Epidermis helper tokens<textarea value={f.epidermis} onChange={e => set("epidermis",e.target.value)} /><small>Keratin, PanCK, EPCAM…</small></label></div></section>

            <section className="builderCard" id="builder-3"><div className="cardTitle"><span>04</span><div><h2>{labels.output}</h2><p>Resolution, archive, and human safety gates</p></div></div><div className="fieldGrid three"><label>Analysis downsample<input type="number" min="1" step="1" value={f.analysisDownsample} onChange={e => set("analysisDownsample",+e.target.value)} /></label><label>Export downsample<input type="number" min="1" step="0.5" value={f.exportDownsample} onChange={e => set("exportDownsample",+e.target.value)} /></label><label>Residual tolerance (°)<input type="number" min="1" max="90" value={f.residual} onChange={e => set("residual",+e.target.value)} /></label></div><div className="toggleGrid"><Toggle label="Full-resolution PNG" value={f.savePng} onChange={v=>set("savePng",v)} /><Toggle label="Native multichannel OME-TIFF" value={f.saveOme} onChange={v=>set("saveOme",v)} /><Toggle label="Human grid approval" value={f.gridApproval} onChange={v=>set("gridApproval",v)} /><Toggle label="Human orientation approval" value={f.orientationApproval} onChange={v=>set("orientationApproval",v)} /></div></section>
          </div>

          <aside className="jsonInspector"><div className="inspectorHead"><div><p>JSON</p><h2>{labels.preview}</h2></div><span>{new Blob([json]).size.toLocaleString()} bytes</span></div>{errors.length > 0 && <div className="errorList">{errors.map(e=><p key={e}><i className="ri-error-warning-line" />{e}</p>)}</div>}<pre>{json}</pre><button disabled={!!errors.length} onClick={download}><i className="ri-download-2-line" />{labels.download}</button><p className="privacy"><i className="ri-lock-2-line" />Generated entirely in your browser. No slide data is uploaded.</p></aside>
        </div>
      </div>
    </main>
  );
}

function Toggle({label,value,onChange}:{label:string;value:boolean;onChange:(v:boolean)=>void}) {
  return <label className="toggle"><span><b>{label}</b><small>{value ? "Enabled" : "Disabled"}</small></span><input type="checkbox" checked={value} onChange={e=>onChange(e.target.checked)} /><i /></label>;
}
