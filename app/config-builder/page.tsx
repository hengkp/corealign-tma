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
  markers: string;
};

const initial: FormState = {
  tissue: "skin",
  output: "presentation",
  nuclear: "dapi, hoechst, nuclear",
  surface: "keratin, cytokeratin, panck, epcam",
  markers: "",
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
        presentation: {
          enabled: false,
          channelTokens: tokens(form.markers),
          gradeMode: "shared_slide_range",
          lowPercentile: 0.5,
          highPercentile: 0.998,
          gamma: 0.85,
          maxChannels: 6,
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
  const json = useMemo(() => JSON.stringify(makeConfig(form), null, 2), [form]);

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <main className="builderPage">
      <a className="skipLink" href="#builder">Skip to builder</a>
      <SiteHeader />

      <section className="builderIntro" id="builder">
        <p className="kicker"><i className="ri-magic-line" /> Config builder</p>
        <h1>Two choices. One ready-to-use config.</h1>
        <p>CoreAlign detects rows, columns, core size, and array position automatically.</p>
      </section>

      <section className="builderLayout">
        <div className="builderForm">
          <section className="choiceSection">
            <div className="choiceHeading"><span>1</span><div><h2>Choose the tissue</h2><p>This sets the direction used for rotation.</p></div></div>
            <div className="choiceGrid">
              <Choice
                selected={form.tissue === "skin"}
                icon="ri-focus-2-line"
                title="Skin"
                text="Keep the epidermis at the top"
                badge="Recommended"
                onClick={() => set("tissue", "skin")}
              />
              <Choice
                selected={form.tissue === "other"}
                icon="ri-shape-line"
                title="Other tissue"
                text="Keep the strongest outer edge at the top"
                onClick={() => set("tissue", "other")}
              />
            </div>
          </section>

          <section className="choiceSection">
            <div className="choiceHeading"><span>2</span><div><h2>Choose the output</h2><p>You can upgrade later without repeating accepted work.</p></div></div>
            <div className="choiceGrid">
              <Choice
                selected={form.output === "presentation"}
                icon="ri-slideshow-3-line"
                title="Presentation images"
                text="Full resolution PNG files"
                badge="Faster"
                onClick={() => set("output", "presentation")}
              />
              <Choice
                selected={form.output === "research"}
                icon="ri-stack-line"
                title="Research package"
                text="PNG, OME-TIFF, and QuPath project"
                onClick={() => set("output", "research")}
              />
            </div>
          </section>

          <div className="automaticStrip"><i className="ri-radar-line" /><div><b>Geometry is automatic</b><span>No row count, column count, or core diameter is required.</span></div><i className="ri-checkbox-circle-fill" /></div>

          <details className="optionalSettings">
            <summary><span><i className="ri-settings-3-line" /> Optional marker channels</span><small>Most users can skip this</small></summary>
            <div className="optionalBody">
              <label><span>Nuclear channel words</span><input value={form.nuclear} onChange={(event) => set("nuclear", event.target.value)} /></label>
              <label><span>Surface marker words</span><input value={form.surface} onChange={(event) => set("surface", event.target.value)} /></label>
              <label><span>Presentation PNG markers</span><input value={form.markers} placeholder="DAPI, PanCK, Ki67" onChange={(event) => set("markers", event.target.value)} /><small>Leave blank for automatic colors. The same grading is used for every core on this slide.</small></label>
            </div>
          </details>
        </div>

        <aside className="builderAside" aria-label="Configuration summary">
          <div className="configReady">
            <div className="readyTitle"><span><i className="ri-checkbox-circle-fill" /></span><div><p>Your config</p><h2>Ready</h2></div></div>
            <dl>
              <div><dt>Tissue</dt><dd>{form.tissue === "skin" ? "Skin" : "Other"}</dd></div>
              <div><dt>Detection</dt><dd>Automatic</dd></div>
              <div><dt>Output</dt><dd>{form.output === "presentation" ? "PNG" : "Research"}</dd></div>
              <div><dt>Review</dt><dd>Human checked</dd></div>
            </dl>
            <a className="downloadConfig" href={`data:application/json;charset=utf-8,${encodeURIComponent(`${json}\n`)}`} download="corealign.config.json"><i className="ri-download-2-line" /> Download config</a>
            <p className="localOnly"><i className="ri-lock-2-line" /> Created in your browser. Nothing is uploaded.</p>
          </div>

          <div className="runNext">
            <h3>Then run it</h3>
            <ol>
              <li><span>1</span><p>Put the config beside your slide and <b>CoreAlign.groovy</b>.</p></li>
              <li><span>2</span><p>Open the slide in QuPath and run the script.</p></li>
              <li><span>3</span><p>Open <b>REPORT.html</b> to review and continue.</p></li>
            </ol>
          </div>
        </aside>
      </section>
    </main>
  );
}

function Choice({ selected, icon, title, text, badge, onClick }: { selected: boolean; icon: string; title: string; text: string; badge?: string; onClick: () => void }) {
  return (
    <button type="button" className={`choiceCard${selected ? " selected" : ""}`} aria-pressed={selected} onClick={onClick}>
      <span className="choiceIcon"><i className={icon} /></span>
      <span className="choiceCopy"><b>{title}</b><small>{text}</small></span>
      {badge && <em>{badge}</em>}
      <i className={selected ? "ri-checkbox-circle-fill selectedMark" : "ri-checkbox-blank-circle-line selectedMark"} />
    </button>
  );
}
