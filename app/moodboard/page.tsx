import type { Metadata } from "next";
import SiteHeader from "../site-header";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export const metadata: Metadata = {
  title: "CoreAlign Moodboard",
  description:
    "The visual direction for CoreAlign: bright scientific workbench, evidence first, one decision at a time.",
};

const feelings = [
  {
    icon: "ri-eye-2-line",
    title: "Evidence first",
    text: "The tissue image is the largest thing on every screen. Interface colour never covers a core, a circle, or an annotation.",
  },
  {
    icon: "ri-focus-3-line",
    title: "One decision at a time",
    text: "Each screen answers one question and offers one primary action. Anything else is quieter, later, or automatic.",
  },
  {
    icon: "ri-sun-line",
    title: "Bright, not clinical",
    text: "White canvas, saturated accents, generous rounding. A workbench you want to spend an afternoon at, not a hospital form.",
  },
  {
    icon: "ri-shield-check-line",
    title: "Honest about doubt",
    text: "Flagged is yellow, missing is red, accepted is green, and every one of them carries a word as well as a colour.",
  },
];

const palette = [
  { name: "Canvas", hex: "#ffffff", role: "Page background in light mode", ink: "#171842" },
  { name: "Ink", hex: "#171842", role: "Body text and headings", ink: "#ffffff" },
  { name: "Primary blue", hex: "#4262ff", role: "The single primary action", ink: "#ffffff" },
  { name: "Cyan", hex: "#00a6d9", role: "Automatically detected cores", ink: "#04121a" },
  { name: "Mint", hex: "#1aa998", role: "Accepted and human corrected", ink: "#04120f" },
  { name: "Yellow", hex: "#ffd02f", role: "Needs a look", ink: "#3a2c00" },
  { name: "Coral", hex: "#ec6972", role: "Missing or failed", ink: "#2b0507" },
  { name: "Violet", hex: "#8055df", role: "Unsaved change by a person", ink: "#ffffff" },
  { name: "Night", hex: "#070b21", role: "Page background in dark mode", ink: "#f7f8ff" },
];

const references = [
  {
    app: "Square",
    pattern: "One outstanding task, one button",
    lesson:
      "A waiting task is a headline and a single filled button, not a list of options. CoreAlign says what it found, then offers exactly one way forward.",
    url: "https://mobbin.com/screens/c3a895a2-9c2b-4d81-ad9c-cbfd0c6031da",
  },
  {
    app: "Melio",
    pattern: "Tinted strip above the content",
    lesson:
      "Status lives in a full width strip pinned under the header, so it survives scrolling without stealing the page.",
    url: "https://mobbin.com/screens/8e64e2d8-e426-494e-b3ab-ec3c78c35456",
  },
  {
    app: "Xero",
    pattern: "Two option cards, one marked recommended",
    lesson:
      "A binary choice with a recommendation is faster than a form. CoreAlign asks two of these and nothing else.",
    url: "https://mobbin.com/screens/2ada8534-eb4c-4bc9-81d6-e2d37cfb4d97",
  },
  {
    app: "Lovable",
    pattern: "Pick your style, then Next",
    lesson:
      "Two thumbnails and one arrow button. Proof that a setup screen can be finished in a second.",
    url: "https://mobbin.com/screens/7a3f1fa3-daa8-4ed7-bd53-eee9c326c1e3",
  },
  {
    app: "Mural",
    pattern: "Palette as labelled cards",
    lesson:
      "Every swatch carries a name and a hex value, so the palette is usable rather than decorative.",
    url: "https://mobbin.com/sites/sections/617cde5a-9515-4ce1-b1f2-5e78811b29ef",
  },
  {
    app: "Sprig",
    pattern: "Principles as icon plus two lines",
    lesson:
      "Short principle cards are read. Paragraphs of philosophy are not.",
    url: "https://mobbin.com/sites/sections/8879fc48-b65b-4f6d-82fb-5cefe888ae06",
  },
];

const voice = [
  {
    good: "117 cores found, 9 positions empty",
    bad: "Detection completed successfully with high confidence",
    why: "A number a reviewer can check beats a claim they cannot.",
  },
  {
    good: "Grid is correct",
    bad: "Approve grid and continue to orientation processing",
    why: "The button says what the person believes, not what the software will do next.",
  },
  {
    good: "Missed a core? Draw an ellipse over it and name it TMA correction.",
    bad: "Users may optionally provide correction annotations to override detection.",
    why: "Instructions are addressed to one person doing one thing.",
  },
  {
    good: "Nothing is exported until you have seen the result and approved it.",
    bad: "A robust human in the loop quality gate protects data integrity.",
    why: "Say the guarantee. Never advertise it.",
  },
];

const bans = [
  "Decorative accent bars and coloured stripes under titles",
  "Colour as the only carrier of a status",
  "Motion that has to finish before a person can act",
  "Real patient tissue used as decoration anywhere",
  "Two primary buttons competing on one screen",
  "A dialog that restates what the page already shows",
];

export default function Moodboard() {
  return (
    <main className="prosePage">
      <a className="skipLink" href="#content">Skip to content</a>
      <SiteHeader />

      <section className="proseHero" id="content">
        <p className="kicker"><i className="ri-palette-line" /> Moodboard</p>
        <h1>Bright scientific workbench.</h1>
        <p className="lead">
          CoreAlign is used by researchers and pathologists who are looking at tissue, not at
          software. This page fixes how it should feel, so a new screen can be judged in a
          minute instead of argued about for a week.
        </p>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">The feeling</p>
          <h2>Four things every screen has to be.</h2>
        </div>
        <div className="moodGrid">
          {feelings.map((item, index) => (
            <article key={item.title} className={`moodCard tone${index + 1}`}>
              <span className="moodIcon"><i className={item.icon} /></span>
              <h3>{item.title}</h3>
              <p>{item.text}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Texture</p>
          <h2>Imagery is synthetic, always.</h2>
          <p>
            Illustration exists to explain the order of operations: find the core, rotate the
            core, then crop it. Real slides appear only as the reviewer&apos;s own data.
          </p>
        </div>
        <figure className="moodFigure">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroArtLight" src={`${basePath}/images/corealign-hero-v2-light.webp`} width="1693" height="929" alt="Synthetic TMA cores moving through detection, rotation, and crop" />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroArtDark" src={`${basePath}/images/corealign-hero-v2-dark.webp`} width="1692" height="929" alt="The same synthetic sequence rendered for dark mode" />
          <figcaption>
            Synthetic cores only. Generated imagery is converted to PNG or WebP before use, and
            never carries a patient identifier.
          </figcaption>
        </figure>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Colour</p>
          <h2>Nine colours. Each one has a job.</h2>
          <p>A colour without a job is decoration, and decoration is what makes a QC image harder to read.</p>
        </div>
        <div className="swatchGrid">
          {palette.map((swatch) => (
            <article key={swatch.name} className="swatchCard">
              <div className="swatchChip" style={{ background: swatch.hex, color: swatch.ink }}>
                <span>{swatch.name}</span>
              </div>
              <div className="swatchMeta">
                <code>{swatch.hex}</code>
                <p>{swatch.role}</p>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Voice</p>
          <h2>Write the number, not the adjective.</h2>
        </div>
        <div className="voiceList">
          {voice.map((line) => (
            <article key={line.good} className="voiceRow">
              <p className="voiceGood"><i className="ri-checkbox-circle-fill" /> {line.good}</p>
              <p className="voiceBad"><i className="ri-close-circle-line" /> {line.bad}</p>
              <p className="voiceWhy">{line.why}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Where it comes from</p>
          <h2>Patterns taken from shipped products.</h2>
          <p>Each row is a real screen that solved the same problem, and the one thing CoreAlign took from it.</p>
        </div>
        <div className="refList">
          {references.map((item) => (
            <article key={item.app} className="refRow">
              <div className="refHead">
                <strong>{item.app}</strong>
                <span>{item.pattern}</span>
              </div>
              <p>{item.lesson}</p>
              <a href={item.url} target="_blank" rel="noopener noreferrer">
                Open on Mobbin <i className="ri-external-link-line" />
              </a>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Never</p>
          <h2>Six things that fail review.</h2>
        </div>
        <ul className="banList">
          {bans.map((ban) => (
            <li key={ban}><i className="ri-forbid-2-line" /> {ban}</li>
          ))}
        </ul>
      </section>

      <section className="startCta">
        <div>
          <p className="eyebrow">Next</p>
          <h2>The rules that turn this into a screen.</h2>
        </div>
        <a className="button" href={`${basePath}/playbook/`}>Open the design playbook <i className="ri-arrow-right-line" /></a>
      </section>

      <footer>
        <a className="siteBrand" href={`${basePath}/`}><span className="brandIcon"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <p>Research software for TMA image preparation. Review results before clinical use.</p>
        <div>
          <a href={`${basePath}/guide/`}>Manual</a>
          <a href={`${basePath}/playbook/`}>Playbook</a>
          <a href={`${basePath}/docs/`}>Reference</a>
          <a href="https://github.com/hengkp/corealign-tma">GitHub</a>
        </div>
      </footer>
    </main>
  );
}
