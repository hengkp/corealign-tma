"use client";

import { useMemo, useState } from "react";
import SiteHeader from "../site-header";

type OutputMode = "presentation" | "research";
type DetailMode = "fast" | "balanced" | "high";
type TissueMode = "skin" | "generic";

type FormState = {
  tissue: TissueMode;
  profile: string;
  description: string;
  rows: number;
  columns: number;
  diameter: number;
  output: OutputMode;
  detail: DetailMode;
  nuclear: string;
  epidermis: string;
};

const initial: FormState = {
  tissue: "skin",
  profile: "auto",
  description: "Automatic TMA geometry",
  rows: 18,
  columns: 7,
  diameter: 0.6,
  output: "presentation",
  detail: "balanced",
  nuclear: "dapi, hoechst, nuclear",
  epidermis: "keratin, cytokeratin, panck, epcam",
};

function tokens(value: string) {
  return value.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean);
}

function makeConfig(form: FormState) {
  const analysisDownsample = form.detail === "fast" ? 6 : form.detail === "high" ? 3 : 4;
  const generic = form.tissue === "generic";
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
          cropPaddingFactor: generic ? 1.4 : 1.75,
          rowScheme: "1, 2, 3...",
          columnScheme: "A, B, C...",
          showAdvancedDialog: false,
          autoDetectGeometry: true,
          autoInferLayout: true,
          useExistingGridUnlessRectangleSelected: true,
          trustNondefaultExistingGrid: true,
        },
        detection: {
          algorithmVersion: generic ? "generic-tma-detect-1" : "skin-tma-detect-2.4-auto-geometry",
          channelMode: "nuclear",
          customChannels: "",
          downsample: 8,
          blurSigmaFraction: 0.25,
          otsuThresholdScale: 0.7,
          minBlobAreaFraction: 0.05,
          maxBlobAreaFraction: 5,
          minAssignedFractionToBuildGrid: 0.3,
          minAssignedFractionForReview: 0.75,
          requireEveryRowAndColumn: true,
          maxMissingFractionToPreserve: 0.06,
        },
        orientation: {
          algorithmVersion: generic ? "generic-peripheral-orient-3.7-rotated-multichannel" : "skin-epidermis-orient-3.7-rotated-multichannel",
          analysisDownsample,
          exportDownsample: 1,
          previewMaxPixels: 900,
          cropScale: 1.05,
          rotationSupportScale: 1.45,
          regionRefinementEnabled: true,
          regionSearchScale: 1.55,
          regionMaxCenterShiftFraction: 0.3,
          regionTissueMargin: 1.12,
          regionMaxCropScale: 1.15,
          regionReviewConfidence: 0.12,
          saveFullResolutionPng: true,
          saveNativeOmeTiff: false,
          saveRotatedMultichannelOmeTiff: form.output === "research",
          cropOverrideClassName: "TMA crop override",
          postRotationToleranceDeg: 12,
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
          requireHumanGridApproval: true,
          requireHumanOrientationApproval: true,
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
    ...(form.diameter <= 0 ? ["Core diameter must be greater than zero"] : []),
    ...(tokens(form.nuclear).length === 0 ? ["Add a DAPI or nuclear channel name"] : []),
  ];

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <main className="builderPage">
      <SiteHeader />

      <section className="builderHero" id="content">
        <div>
          <p className="kicker"><i className="ri-magic-line" /> Config Builder</p>
          <h1>Create your TMA config in under a minute.</h1>
          <p>Choose the output only. CoreAlign detects rows and columns from the slide.</p>
        </div>
        <div className="builderSteps" aria-label="Three builder steps">
          <span className="active"><b>1</b> Auto detect</span><i className="ri-arrow-right-s-line" />
          <span><b>2</b> Output</span><i className="ri-arrow-right-s-line" />
          <span><b>3</b> Download</span>
        </div>
      </section>

      <section className="builderLayout">
        <div className="builderForm">
          <section className="formCard presetCard">
            <StepHeading number="1" title="Geometry is automatic" text="No row count, column count, or layout preset is required." />
            <div className="autoGeometryCard">
              <span><i className="ri-radar-line" /></span>
              <div><h3>Automatic TMA detection is on</h3><p>CoreAlign finds the array, estimates rows and columns, removes stray tissue, and validates the result before processing cores.</p></div>
              <em><i className="ri-checkbox-circle-fill" /> Ready</em>
            </div>
            <p className="presetHint"><i className="ri-shield-check-line" /> Ambiguous slides stop safely instead of creating a guessed grid. The validated example is recognized automatically.</p>
          </section>

          <section className="formCard outputCard">
            <StepHeading number="2" title="Choose your output" text="Both options rotate each core first and crop it second." />
            <div className="outputChoices">
              <button type="button" className={form.output === "presentation" ? "selected" : ""} aria-pressed={form.output === "presentation"} onClick={() => set("output", "presentation")}>
                <span className="outputIcon presentation"><i className="ri-slideshow-3-line" /></span>
                <span><b>Presentation images</b><small>Full resolution PNG. Fastest option for PowerPoint and figures.</small></span>
                <em>Recommended</em>
              </button>
              <button type="button" className={form.output === "research" ? "selected" : ""} aria-pressed={form.output === "research"} onClick={() => set("output", "research")}>
                <span className="outputIcon research"><i className="ri-stack-line" /></span>
                <span><b>Research package</b><small>PNG plus all-channel UINT16 OME-TIFF. Larger and slower.</small></span>
              </button>
            </div>
          </section>

          <details className="advancedPanel">
            <summary><span><i className="ri-settings-3-line" /> Advanced settings</span><small>Optional</small></summary>
            <div className="advancedBody">
              <div className="fieldGrid two">
                <Field label="Tissue type" hint="Skin enables epidermis-up orientation">
                  <select value={form.tissue} onChange={(event) => set("tissue", event.target.value as TissueMode)}>
                    <option value="skin">Skin</option>
                    <option value="generic">Other tissue</option>
                  </select>
                </Field>
                <Field label="Profile name" hint="Used in reports and checkpoints">
                  <input value={form.profile} onChange={(event) => set("profile", event.target.value.replace(/[^a-zA-Z0-9_-]/g, "_"))} />
                </Field>
                <Field label="Processing detail" hint="Balanced works for most slides">
                  <select value={form.detail} onChange={(event) => set("detail", event.target.value as DetailMode)}>
                    <option value="fast">Fast</option>
                    <option value="balanced">Balanced</option>
                    <option value="high">High detail</option>
                  </select>
                </Field>
                <Field label="Nuclear channel names" hint="Comma separated. DAPI works for most CyCIF slides.">
                  <input value={form.nuclear} onChange={(event) => set("nuclear", event.target.value)} />
                </Field>
                <Field label="Epidermis marker names" hint="Optional helper markers such as keratin or PanCK">
                  <input value={form.epidermis} onChange={(event) => set("epidermis", event.target.value)} />
                </Field>
                <NumberField label="Fallback rows" value={form.rows} min={1} max={100} step={1} onChange={(value) => set("rows", value)} />
                <NumberField label="Fallback columns" value={form.columns} min={1} max={100} step={1} onChange={(value) => set("columns", value)} />
                <NumberField label="Core-size seed in mm" value={form.diameter} min={0.1} max={5} step={0.1} onChange={(value) => set("diameter", value)} />
              </div>
            </div>
          </details>
        </div>

        <aside className="builderAside" aria-label="Configuration summary">
          <div className={`builderSummary ${errors.length ? "hasError" : "isReady"}`}>
            <div className="summaryTop"><span><i className={errors.length ? "ri-error-warning-fill" : "ri-checkbox-circle-fill"} /></span><div><p>Your config</p><h2>{errors.length ? "Check the highlighted fields" : "Ready to download"}</h2></div></div>
            <dl>
              <div><dt>Array</dt><dd>Auto detect</dd></div>
              <div><dt>Rows and columns</dt><dd>No input</dd></div>
              <div><dt>Safety</dt><dd>Confidence checked</dd></div>
              <div><dt>Output</dt><dd>{form.output === "presentation" ? "PNG" : "PNG + OME-TIFF"}</dd></div>
              <div><dt>Review</dt><dd>Human check included</dd></div>
            </dl>
            {errors.length > 0 && <div className="errorList">{errors.map((error) => <p key={error}>{error}</p>)}</div>}
            <a
              className={`downloadButton ${errors.length ? "disabled" : ""}`}
              aria-disabled={errors.length > 0}
              href={errors.length ? undefined : `data:application/json;charset=utf-8,${encodeURIComponent(`${json}\n`)}`}
              download="corealign.config.json"
            ><i className="ri-download-2-line" /> Download config</a>
            <p className="privacyNote"><i className="ri-lock-2-line" /> Nothing is uploaded. The file is created in your browser.</p>
          </div>

          <div className="afterDownload">
            <p>Next in QuPath</p>
            <ol>
              <li><span>1</span><p>Put the slide and <b>CoreAlign.groovy</b> in one folder. Config is optional.</p></li>
              <li><span>2</span><p>Open that slide copy in QuPath.</p></li>
              <li><span>3</span><p>Run <b>CoreAlign.groovy</b> and check the preview.</p></li>
            </ol>
          </div>
        </aside>
      </section>
    </main>
  );
}

function StepHeading({ number, title, text }: { number: string; title: string; text: string }) {
  return <div className="stepHeading"><span>{number}</span><div><h2>{title}</h2><p>{text}</p></div></div>;
}

function NumberField({ label, value, min, max, step, onChange }: { label: string; value: number; min: number; max: number; step: number; onChange: (value: number) => void }) {
  return <label className="numberField"><span>{label}</span><input type="number" min={min} max={max} step={step} value={value} onChange={(event) => onChange(Number(event.target.value))} /></label>;
}

function Field({ label, hint, children }: { label: string; hint: string; children: React.ReactNode }) {
  return <label className="field"><span>{label}</span>{children}<small>{hint}</small></label>;
}
