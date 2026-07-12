"use client";

import { useMemo, useState } from "react";
import ThemeToggle from "../theme-toggle";

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

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

const presets: Record<string, Partial<FormState>> = {
  "Skin 18 by 7": {
    profile: "skin_18x7",
    description: "0.6 mm skin CyCIF TMA",
    rows: 18,
    columns: 7,
    diameter: 0.6,
    nuclear: "dapi, hoechst, nuclear",
    epidermis: "keratin, cytokeratin, panck, epcam",
  },
  "Skin 12 by 8": {
    profile: "skin_12x8",
    description: "Configurable skin TMA",
    rows: 12,
    columns: 8,
    diameter: 1,
    nuclear: "dapi, hoechst, nuclear",
    epidermis: "keratin, panck, epcam",
  },
  "Generic TMA": {
    profile: "generic_tma",
    description: "Generic peripheral tissue orientation",
    rows: 10,
    columns: 10,
    diameter: 1,
    nuclear: "dapi, hoechst, nuclear",
    epidermis: "keratin, panck, epcam",
  },
};

const initial: FormState = {
  profile: "skin_18x7",
  description: "0.6 mm skin CyCIF TMA",
  rows: 18,
  columns: 7,
  diameter: 0.6,
  nuclear: "dapi, hoechst, nuclear",
  epidermis: "keratin, cytokeratin, panck, epcam",
  analysisDownsample: 4,
  exportDownsample: 1,
  savePng: true,
  saveOme: true,
  residual: 12,
  gridApproval: true,
  orientationApproval: true,
};

function tokens(value: string) {
  return value.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean);
}

function makeConfig(form: FormState) {
  return {
    schemaVersion: 1,
    activeProfile: form.profile,
    profiles: {
      [form.profile]: {
        description: form.description,
        grid: {
          rows: form.rows,
          columns: form.columns,
          coreDiameterMM: form.diameter,
          cropPaddingFactor: 1.75,
          rowScheme: "1, 2, 3...",
          columnScheme: "A, B, C...",
          showAdvancedDialog: true,
          useExistingGridUnlessRectangleSelected: true,
          trustNondefaultExistingGrid: true,
        },
        detection: {
          algorithmVersion: "skin-tma-detect-2.2-nuclear-rescue",
          channelMode: "nuclear",
          customChannels: "",
          downsample: 8,
          blurSigmaFraction: 0.25,
          otsuThresholdScale: 0.7,
          minBlobAreaFraction: 0.05,
          maxBlobAreaFraction: 5,
          minAssignedFractionToBuildGrid: 0.3,
          maxMissingFractionToPreserve: 0.06,
        },
        orientation: {
          algorithmVersion: "skin-epidermis-orient-3.7-rotated-multichannel",
          analysisDownsample: form.analysisDownsample,
          exportDownsample: form.exportDownsample,
          previewMaxPixels: 900,
          cropScale: 1.05,
          rotationSupportScale: 1.45,
          regionRefinementEnabled: true,
          regionSearchScale: 1.55,
          regionMaxCenterShiftFraction: 0.3,
          regionTissueMargin: 1.12,
          regionMaxCropScale: 1.15,
          regionReviewConfidence: 0.12,
          saveFullResolutionPng: form.savePng,
          saveNativeOmeTiff: false,
          saveRotatedMultichannelOmeTiff: form.saveOme,
          cropOverrideClassName: "TMA crop override",
          postRotationToleranceDeg: form.residual,
          postRotationMaxIterations: 2,
          angularSectors: 72,
          outerRingInner: 0.42,
          outerRingOuter: 1.02,
          tissueThresholdScale: 0.55,
          reviewConfidence: 0.12,
          okConfidence: 0.28,
          nuclearChannelTokens: tokens(form.nuclear),
          epidermisChannelTokens: tokens(form.epidermis),
          rgbRedChannelTokens: tokens(form.epidermis),
          rgbGreenChannelTokens: [],
          overrideClassName: "Epidermis override",
        },
        quality: {
          requireHumanGridApproval: form.gridApproval,
          requireHumanOrientationApproval: form.orientationApproval,
          blockPresentationWhenAnySelectedCoreNeedsReview: true,
        },
        presentation: {
          enabled: false,
          title: "TMA presentation",
          conditions: [],
          treatmentColumns: [],
          comparisons: [],
        },
      },
    },
  };
}

export default function ConfigBuilder() {
  const [form, setForm] = useState<FormState>(initial);
  const config = useMemo(() => makeConfig(form), [form]);
  const json = useMemo(() => JSON.stringify(config, null, 2), [config]);
  const errors = [
    ...(form.rows < 1 || form.rows > 100 ? ["Rows must be between 1 and 100"] : []),
    ...(form.columns < 1 || form.columns > 100 ? ["Columns must be between 1 and 100"] : []),
    ...(form.diameter <= 0 ? ["Core diameter must be positive"] : []),
    ...(tokens(form.nuclear).length === 0 ? ["Add at least one nuclear channel token"] : []),
    ...(form.exportDownsample < 1 ? ["Export downsample must be at least 1"] : []),
  ];

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function download() {
    if (errors.length) return;
    const blob = new Blob([`${json}\n`], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "corealign.config.json";
    anchor.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main className="builderPage">
      <header className="builderHeader">
        <a className="siteBrand" href={`${basePath}/`}>
          <span className="brandIcon"><i className="ri-focus-3-line" /></span>
          <span>CoreAlign <b>TMA</b></span>
        </a>
        <div className="builderHeaderTitle"><span>Config Builder</span><small>Create a ready to use profile</small></div>
        <div className="builderHeaderActions">
          <ThemeToggle />
          <a className="textLink" href="https://github.com/hengkp/corealign-tma"><i className="ri-github-fill" /> GitHub</a>
        </div>
      </header>

      <section className="builderHero">
        <div>
          <p className="kicker"><i className="ri-settings-4-line" /> Visual configuration</p>
          <h1>Describe your array. Download the config.</h1>
          <p>Use plain language fields. The JSON updates instantly and stays in your browser.</p>
        </div>
        <div className={`builderStatus ${errors.length ? "error" : "ready"}`}>
          <i className={errors.length ? "ri-error-warning-line" : "ri-checkbox-circle-fill"} />
          <div><b>{errors.length ? "Check required fields" : "Ready to download"}</b><span>{form.rows * form.columns} positions and source resolution export</span></div>
        </div>
      </section>

      <section className="builderLayout">
        <aside className="builderRail" aria-label="Configuration sections">
          {[
            ["01", "Profile", "ri-profile-line"],
            ["02", "Array", "ri-layout-grid-line"],
            ["03", "Channels", "ri-contrast-drop-2-line"],
            ["04", "Output", "ri-save-3-line"],
          ].map(([number, label, icon]) => (
            <a key={number} href={`#section-${number}`}><span>{number}</span><i className={icon} />{label}</a>
          ))}
          <div className="railNote"><i className="ri-lock-2-line" /><p><b>Local by design</b> No slide data is uploaded.</p></div>
        </aside>

        <div className="builderForm">
          <section className="formCard presetCard">
            <div className="formHeading"><div><p>Start here</p><h2>Choose a preset</h2></div><i className="ri-sparkling-2-line" /></div>
            <div className="presetButtons">
              {Object.entries(presets).map(([name, value]) => (
                <button key={name} onClick={() => setForm((current) => ({ ...current, ...value }))}>
                  <span><b>{name}</b><small>{value.diameter} mm cores</small></span><i className="ri-arrow-right-line" />
                </button>
              ))}
            </div>
          </section>

          <section className="formCard" id="section-01">
            <div className="formHeading"><div><p>Section 01</p><h2>Profile</h2></div><i className="ri-profile-line" /></div>
            <div className="fieldGrid two">
              <Field label="Profile ID" hint="Letters, numbers, dash, and underscore">
                <input value={form.profile} onChange={(event) => set("profile", event.target.value.replace(/[^a-zA-Z0-9_-]/g, "_"))} />
              </Field>
              <Field label="Description" hint="A short name for this array">
                <input value={form.description} onChange={(event) => set("description", event.target.value)} />
              </Field>
            </div>
          </section>

          <section className="formCard" id="section-02">
            <div className="formHeading"><div><p>Section 02</p><h2>Array geometry</h2></div><i className="ri-layout-grid-line" /></div>
            <div className="fieldGrid three">
              <Field label="Rows" hint="Vertical positions"><input type="number" min="1" max="100" value={form.rows} onChange={(event) => set("rows", Number(event.target.value))} /></Field>
              <Field label="Columns" hint="Horizontal positions"><input type="number" min="1" max="100" value={form.columns} onChange={(event) => set("columns", Number(event.target.value))} /></Field>
              <Field label="Core diameter in mm" hint="Physical punch size"><input type="number" min="0.1" step="0.1" value={form.diameter} onChange={(event) => set("diameter", Number(event.target.value))} /></Field>
            </div>
            <div className="arrayPreview" style={{ gridTemplateColumns: `repeat(${Math.min(form.columns, 14)}, 1fr)` }}>
              {Array.from({ length: Math.min(form.rows * form.columns, 140) }, (_, index) => <span key={index} />)}
            </div>
          </section>

          <section className="formCard" id="section-03">
            <div className="formHeading"><div><p>Section 03</p><h2>Channel mapping</h2></div><i className="ri-contrast-drop-2-line" /></div>
            <div className="fieldGrid two">
              <Field label="Nuclear channel tokens" hint="Examples: DAPI, Hoechst, nuclear"><textarea value={form.nuclear} onChange={(event) => set("nuclear", event.target.value)} /></Field>
              <Field label="Epidermis helper tokens" hint="Examples: keratin, PanCK, EPCAM"><textarea value={form.epidermis} onChange={(event) => set("epidermis", event.target.value)} /></Field>
            </div>
          </section>

          <section className="formCard" id="section-04">
            <div className="formHeading"><div><p>Section 04</p><h2>Output and review</h2></div><i className="ri-save-3-line" /></div>
            <div className="fieldGrid three">
              <Field label="Analysis downsample" hint="Higher is faster"><input type="number" min="1" value={form.analysisDownsample} onChange={(event) => set("analysisDownsample", Number(event.target.value))} /></Field>
              <Field label="Export downsample" hint="1 keeps source dimensions"><input type="number" min="1" step="0.5" value={form.exportDownsample} onChange={(event) => set("exportDownsample", Number(event.target.value))} /></Field>
              <Field label="Residual tolerance" hint="Angle in degrees"><input type="number" min="1" max="90" value={form.residual} onChange={(event) => set("residual", Number(event.target.value))} /></Field>
            </div>
            <div className="toggleGrid">
              <Toggle label="Full resolution PNG" detail="For figures and slides" value={form.savePng} onChange={(value) => set("savePng", value)} />
              <Toggle label="Rotated multichannel OME TIFF" detail="For archive and analysis" value={form.saveOme} onChange={(value) => set("saveOme", value)} />
              <Toggle label="Human grid approval" detail="Review before processing" value={form.gridApproval} onChange={(value) => set("gridApproval", value)} />
              <Toggle label="Human orientation approval" detail="Approve before presentation" value={form.orientationApproval} onChange={(value) => set("orientationApproval", value)} />
            </div>
          </section>
        </div>

        <aside className="jsonPanel">
          <div className="jsonHeader"><div><p>Live output</p><h2>corealign.config.json</h2></div><span>{new Blob([json]).size.toLocaleString()} bytes</span></div>
          {errors.length > 0 && <div className="errorList">{errors.map((error) => <p key={error}><i className="ri-error-warning-line" /> {error}</p>)}</div>}
          <pre>{json}</pre>
          <button className="downloadButton" disabled={errors.length > 0} onClick={download}><i className="ri-download-2-line" /> Download config</button>
          <p className="jsonFoot"><i className="ri-shield-check-line" /> Validated in your browser</p>
        </aside>
      </section>
    </main>
  );
}

function Field({ label, hint, children }: { label: string; hint: string; children: React.ReactNode }) {
  return <label className="field"><span>{label}</span>{children}<small>{hint}</small></label>;
}

function Toggle({ label, detail, value, onChange }: { label: string; detail: string; value: boolean; onChange: (value: boolean) => void }) {
  return (
    <label className="toggle">
      <span><b>{label}</b><small>{detail}</small></span>
      <input type="checkbox" checked={value} onChange={(event) => onChange(event.target.checked)} />
      <i />
    </label>
  );
}
