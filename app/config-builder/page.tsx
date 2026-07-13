"use client";

import { useMemo, useState } from "react";
import SiteHeader from "../site-header";

type OutputMode = "presentation" | "research";
type TissueMode = "skin" | "other";

type FormState = {
  tissue: TissueMode;
  output: OutputMode;
  nuclear: string;
  surface: string;
};

const initial: FormState = {
  tissue: "skin",
  output: "presentation",
  nuclear: "dapi, hoechst, nuclear",
  surface: "keratin, cytokeratin, panck, epcam",
};

function tokens(value: string) {
  return value.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean);
}

function makeConfig(form: FormState) {
  const skin = form.tissue === "skin";
  return {
    schemaVersion: 2,
    activeProfile: "automatic",
    profiles: {
      automatic: {
        description: skin ? "Automatic skin TMA with epidermis at the top" : "Automatic TMA with a consistent tissue edge at the top",
        grid: {
          geometryMode: "automatic",
          coreDiameterMode: "automatic",
          cropPaddingFactor: 1.9,
          autoDetectGeometry: true,
          autoEstimateCoreDiameter: true,
          autoInferLayout: true,
          showAdvancedDialog: false,
          useExistingGridUnlessRectangleSelected: true,
          trustNondefaultExistingGrid: true,
          exportQc: true,
        },
        detection: {
          algorithmVersion: "corealign-grid-3.0-adaptive",
          channelMode: "nuclear",
          autoRetryMergedChannels: true,
          minAssignedFractionToBuildGrid: 0.3,
          minAssignedFractionForReview: 0.75,
          requireEveryRowAndColumn: true,
          maxMissingFractionToPreserve: 0.06,
        },
        orientation: {
          algorithmVersion: skin ? "skin-epidermis-orient-3.7-rotated-multichannel" : "generic-peripheral-orient-3.7-rotated-multichannel",
          analysisDownsample: 4,
          exportDownsample: 1,
          cropScale: 1.05,
          rotationSupportScale: 1.45,
          regionRefinementEnabled: true,
          saveFullResolutionPng: true,
          saveNativeOmeTiff: false,
          saveRotatedMultichannelOmeTiff: form.output === "research",
          nuclearChannelTokens: tokens(form.nuclear),
          epidermisChannelTokens: tokens(form.surface),
          rgbRedChannelTokens: tokens(form.surface),
          rgbGreenChannelTokens: [],
          overrideClassName: skin ? "Epidermis override" : "Orientation override",
        },
        quality: {
          requireHumanGridApproval: true,
          requireHumanOrientationApproval: true,
          blockPresentationWhenAnySelectedCoreNeedsReview: true,
        },
        presentation: { enabled: false, conditions: [], treatmentColumns: [], comparisons: [] },
      },
    },
  };
}

export default function ConfigBuilder() {
  const [form, setForm] = useState<FormState>(initial);
  const json = useMemo(() => JSON.stringify(makeConfig(form), null, 2), [form]);

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <main className="builderPage">
      <SiteHeader />

      <section className="builderHero" id="content">
        <div>
          <p className="kicker"><i className="ri-magic-line" /> Optional Config Builder</p>
          <h1>Choose two things. CoreAlign handles the rest.</h1>
          <p>Rows, columns, core size, channels, and array position are detected from the slide.</p>
        </div>
        <div className="builderSteps" aria-label="Three builder steps">
          <span className="active"><b>1</b> Tissue</span><i className="ri-arrow-right-s-line" />
          <span><b>2</b> Output</span><i className="ri-arrow-right-s-line" />
          <span><b>3</b> Download</span>
        </div>
      </section>

      <section className="builderLayout">
        <div className="builderForm">
          <section className="formCard outputCard">
            <StepHeading number="1" title="What tissue are you preparing?" text="This controls the direction used for consistent rotation." />
            <div className="outputChoices">
              <button type="button" className={form.tissue === "skin" ? "selected" : ""} aria-pressed={form.tissue === "skin"} onClick={() => set("tissue", "skin")}>
                <span className="outputIcon presentation"><i className="ri-focus-2-line" /></span>
                <span><b>Skin</b><small>Places the epidermis at the top of every accepted core.</small></span>
                <em>Recommended</em>
              </button>
              <button type="button" className={form.tissue === "other" ? "selected" : ""} aria-pressed={form.tissue === "other"} onClick={() => set("tissue", "other")}>
                <span className="outputIcon research"><i className="ri-shape-line" /></span>
                <span><b>Other tissue</b><small>Places the strongest peripheral tissue edge at the top.</small></span>
              </button>
            </div>
          </section>

          <section className="formCard outputCard">
            <StepHeading number="2" title="What do you need?" text="Every core is refined, rotated, checked, and then cropped." />
            <div className="outputChoices">
              <button type="button" className={form.output === "presentation" ? "selected" : ""} aria-pressed={form.output === "presentation"} onClick={() => set("output", "presentation")}>
                <span className="outputIcon presentation"><i className="ri-slideshow-3-line" /></span>
                <span><b>Presentation images</b><small>Full resolution PNG files for PowerPoint and figures.</small></span>
                <em>Faster</em>
              </button>
              <button type="button" className={form.output === "research" ? "selected" : ""} aria-pressed={form.output === "research"} onClick={() => set("output", "research")}>
                <span className="outputIcon research"><i className="ri-stack-line" /></span>
                <span><b>Research package</b><small>PNG plus rotated UINT16 OME-TIFF with every channel.</small></span>
              </button>
            </div>
            <p className="presetHint"><i className="ri-history-line" /> Start with Presentation images if you prefer. You can download a Research package config later and run the same script again. CoreAlign reuses every accepted grid, rotation, and crop checkpoint, then creates only the missing OME-TIFF files.</p>
          </section>

          <section className="formCard presetCard">
            <StepHeading number="3" title="Detection is fully automatic" text="No array geometry or punch size is required." />
            <div className="autoGeometryCard">
              <span><i className="ri-radar-line" /></span>
              <div><h3>Automatic slide scan</h3><p>CoreAlign finds the array, estimates core size, detects the grid, and checks confidence before processing.</p></div>
              <em><i className="ri-checkbox-circle-fill" /> On</em>
            </div>
            <p className="presetHint"><i className="ri-shield-check-line" /> If confidence is low, CoreAlign pauses and keeps completed work. It never silently accepts an uncertain grid.</p>
          </section>

          <details className="advancedPanel">
            <summary><span><i className="ri-price-tag-3-line" /> Channel name helper</span><small>Usually not needed</small></summary>
            <div className="advancedBody">
              <div className="fieldGrid two">
                <Field label="Nuclear channel words" hint="CoreAlign already recognizes common DAPI names and falls back safely.">
                  <input value={form.nuclear} onChange={(event) => set("nuclear", event.target.value)} />
                </Field>
                <Field label="Surface marker words" hint="Optional markers that can support skin orientation.">
                  <input value={form.surface} onChange={(event) => set("surface", event.target.value)} />
                </Field>
              </div>
            </div>
          </details>
        </div>

        <aside className="builderAside" aria-label="Configuration summary">
          <div className="builderSummary isReady">
            <div className="summaryTop"><span><i className="ri-checkbox-circle-fill" /></span><div><p>Your config</p><h2>Ready to download</h2></div></div>
            <dl>
              <div><dt>Tissue</dt><dd>{form.tissue === "skin" ? "Skin" : "Other tissue"}</dd></div>
              <div><dt>Geometry</dt><dd>Automatic</dd></div>
              <div><dt>Core size</dt><dd>Automatic</dd></div>
              <div><dt>Output</dt><dd>{form.output === "presentation" ? "PNG" : "PNG and OME-TIFF"}</dd></div>
              <div><dt>Safety</dt><dd>Review exceptions</dd></div>
            </dl>
            <a className="downloadButton" href={`data:application/json;charset=utf-8,${encodeURIComponent(`${json}\n`)}`} download="corealign.config.json"><i className="ri-download-2-line" /> Download config</a>
            <p className="privacyNote"><i className="ri-lock-2-line" /> Nothing is uploaded. The file is created in your browser.</p>
          </div>

          <div className="afterDownload">
            <p>Run in QuPath</p>
            <ol>
              <li><span>1</span><p>Put the slide, <b>CoreAlign.groovy</b>, and this config in one folder.</p></li>
              <li><span>2</span><p>Open the slide and run <b>CoreAlign.groovy</b>.</p></li>
              <li><span>3</span><p>Review the grid and flagged cores. Run the same file to continue.</p></li>
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

function Field({ label, hint, children }: { label: string; hint: string; children: React.ReactNode }) {
  return <label className="field"><span>{label}</span>{children}<small>{hint}</small></label>;
}
