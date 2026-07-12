const repo = "https://github.com/hengkp/corealign-tma";
import ThemeToggle from "./theme-toggle";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

const path = (value: string) => `${basePath}${value}`;

const validation = [
  ["126", "array positions assigned"],
  ["117", "present cores"],
  ["9", "known empty positions"],
  ["0", "processing errors"],
];

const steps = [
  {
    icon: "ri-download-cloud-2-line",
    number: "01",
    title: "Download",
    text: "Get CoreAlign.groovy and a config file from the repository.",
  },
  {
    icon: "ri-layout-grid-line",
    number: "02",
    title: "Detect and review",
    text: "Run once in QuPath. Check the grid and correct only the positions that need attention.",
  },
  {
    icon: "ri-focus-3-line",
    number: "03",
    title: "Rotate then crop",
    text: "Each core is read from a safe source window, rotated, checked, and cropped last.",
  },
  {
    icon: "ri-shield-check-line",
    number: "04",
    title: "Approve and export",
    text: "Review uncertain results, resume changed cores, and export ready to present images.",
  },
];

const reviewTools = [
  ["ri-map-pin-line", "TMA correction", "Move a core center without rebuilding the full array."],
  ["ri-crop-2-line", "Crop override", "Define a cleaner source region for one contaminated core."],
  ["ri-compass-3-line", "Epidermis override", "Point to the correct epidermal side when orientation is wrong."],
];

export default function Home() {
  return (
    <main>
      <a className="skipLink" href="#content">Skip to content</a>

      <header className="siteHeader">
        <a className="siteBrand" href={path("/")} aria-label="CoreAlign TMA home">
          <span className="brandIcon"><i className="ri-focus-3-line" /></span>
          <span>CoreAlign <b>TMA</b></span>
        </a>
        <nav aria-label="Main navigation">
          <a href="#workflow">Workflow</a>
          <a href="#review">Human review</a>
          <a href="#outputs">Outputs</a>
          <a href="#validation">Validation</a>
        </nav>
        <div className="headerActions">
          <ThemeToggle />
          <a className="textLink" href={repo}>GitHub</a>
          <a className="button small" href={path("/config-builder/")}>Build a config</a>
        </div>
      </header>

      <section className="hero" id="content">
        <div className="heroCopy reveal">
          <p className="kicker"><i className="ri-microscope-line" /> QuPath workflow for skin TMA orientation</p>
          <h1>Detect, rotate, and crop every core with review built in.</h1>
          <p className="heroLead">
            CoreAlign TMA turns a full tissue microarray into consistent individual core images.
            It keeps skin epidermis at the top, saves work after every core, and sends uncertain
            results to a focused review queue.
          </p>
          <div className="heroActions">
            <a className="button" href={path("/config-builder/")}>
              Create a config <i className="ri-arrow-right-line" />
            </a>
            <a className="button ghost" href={repo}>
              <i className="ri-github-fill" /> View on GitHub
            </a>
          </div>
          <div className="quickStart">
            <i className="ri-time-line" />
            <div><b>Start with two files</b><span>CoreAlign.groovy and corealign.config.json</span></div>
          </div>
        </div>

        <figure className="heroFigure reveal delayOne">
          {/* Generated WebP is already optimized and must stay portable on GitHub Pages. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={path("/images/synthetic-array-hero.webp")}
            alt="Generated illustration of unordered TMA cores becoming an aligned array"
          />
          <figcaption><i className="ri-sparkling-2-line" /> Generated illustration. No real specimen data.</figcaption>
        </figure>
      </section>

      <section className="validationStrip" aria-label="Reference validation result">
        <div className="validationLabel"><i className="ri-flask-line" /> Reference slide result</div>
        {validation.map(([value, label]) => (
          <div className="metric" key={label}><strong>{value}</strong><span>{label}</span></div>
        ))}
      </section>

      <section className="section workflowSection" id="workflow">
        <div className="sectionIntro">
          <p className="eyebrow">How it works</p>
          <h2>One script. Four clear stages.</h2>
          <p>The workflow stops at the right moments, saves exact checkpoints, and resumes without repeating completed cores.</p>
        </div>
        <div className="stepGrid">
          {steps.map((step) => (
            <article className="stepCard" key={step.number}>
              <div><span>{step.number}</span><i className={step.icon} /></div>
              <h3>{step.title}</h3>
              <p>{step.text}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="section configFeature">
        <div className="configPreview">
          <div className="previewHeader">
            <div><span className="statusDot" /> Config ready</div>
            <span>126 positions</span>
          </div>
          <div className="previewBody">
            <div className="previewNav"><span className="active" /><span /><span /><span /></div>
            <div className="previewFields">
              <span className="field wide" /><div><span className="field" /><span className="field" /></div>
              <div><span className="field" /><span className="field" /></div><span className="field wide" />
            </div>
            <div className="previewCode">
              <code>{`{\n  "rows": 18,\n  "columns": 7,\n  "exportDownsample": 1,\n  "humanReview": true\n}`}</code>
            </div>
          </div>
        </div>
        <div className="configCopy">
          <p className="eyebrow">Visual config builder</p>
          <h2>Set up a new array without editing JSON.</h2>
          <p>Choose a preset, enter the array geometry, map channel names, and download a validated config file. Slide data never leaves your browser.</p>
          <ul>
            <li><i className="ri-check-line" /> Plain language fields</li>
            <li><i className="ri-check-line" /> Live validation</li>
            <li><i className="ri-check-line" /> Ready to use JSON download</li>
          </ul>
          <a className="button" href={path("/config-builder/")}>Open Config Builder <i className="ri-arrow-right-line" /></a>
        </div>
      </section>

      <section className="section reviewSection" id="review">
        <div className="reviewCopy">
          <p className="eyebrow">Human review</p>
          <h2>Automation handles volume. Review handles uncertainty.</h2>
          <p>CoreAlign shows the exact cores that need attention. A reviewer can correct one region and run again. Valid checkpoints stay untouched.</p>
          <div className="reviewTools">
            {reviewTools.map(([icon, title, text]) => (
              <article key={title}><i className={icon} /><div><h3>{title}</h3><p>{text}</p></div></article>
            ))}
          </div>
        </div>
        <figure className="coreFigure">
          {/* Generated WebP is already optimized and must stay portable on GitHub Pages. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={path("/images/synthetic-core-grid.webp")}
            alt="Generated grid of four synthetic skin core illustrations aligned with epidermis at the top"
          />
          <figcaption><span><i className="ri-checkbox-circle-fill" /> Epidermis aligned at the top</span><span>Generated example</span></figcaption>
        </figure>
      </section>

      <section className="section outputSection" id="outputs">
        <div className="sectionIntro compact">
          <p className="eyebrow">Outputs</p>
          <h2>Use the right file for the next task.</h2>
        </div>
        <div className="outputGrid">
          <article>
            <div className="outputIcon"><i className="ri-image-2-line" /></div>
            <div><p>For presentations</p><h3>Full resolution PNG</h3><span>Rotated RGB image at source pixel dimensions for figures, contact sheets, and slides.</span></div>
            <b>PNG</b>
          </article>
          <article>
            <div className="outputIcon"><i className="ri-stack-line" /></div>
            <div><p>For scientific archive</p><h3>Rotated multichannel OME TIFF</h3><span>UINT16 image with every original channel and the accepted transform applied to each plane.</span></div>
            <b>OME TIFF</b>
          </article>
        </div>
      </section>

      <section className="section validationSection" id="validation">
        <div>
          <p className="eyebrow">Accuracy policy</p>
          <h2>One hundred percent reviewed. Never one hundred percent guessed.</h2>
        </div>
        <div className="validationCopy">
          <p>CoreAlign does not claim autonomous biological accuracy. It blocks final presentation status until the exact grid and orientation result hashes are reviewed and accepted.</p>
          <div><i className="ri-lock-2-line" /><span><b>Exact hash approval</b> prevents an old review from approving a changed result.</span></div>
          <div><i className="ri-save-3-line" /><span><b>Atomic checkpoints</b> prevent a failed core from forcing a full restart.</span></div>
        </div>
      </section>

      <section className="finalCta">
        <div>
          <p className="eyebrow">Ready to start</p>
          <h2>Build the config. Run one file. Review what matters.</h2>
        </div>
        <div>
          <a className="button light" href={path("/config-builder/")}>Create a config <i className="ri-arrow-right-line" /></a>
          <a className="button outlineLight" href={repo}><i className="ri-github-fill" /> Download the workflow</a>
        </div>
      </section>

      <footer>
        <a className="siteBrand" href={path("/")}><span className="brandIcon"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <p>Research and quality control software. Pathologist review is required before clinical claims.</p>
        <div><a href={repo}>GitHub</a><a href={`${repo}/blob/main/README.md`}>Documentation</a><a href={`${repo}/blob/main/CREDITS.md`}>Credits</a></div>
      </footer>
    </main>
  );
}
