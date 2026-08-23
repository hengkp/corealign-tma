import type { Metadata } from "next";
import SiteHeader from "../site-header";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export const metadata: Metadata = {
  title: "CoreAlign Design Playbook",
  description:
    "The rules CoreAlign screens are built and reviewed against: one question per screen, one primary action, status carried by word and colour.",
};

const laws = [
  {
    number: "01",
    title: "One question per screen",
    rule: "Decide the single question the reviewer opened this for, and answer it above the fold.",
    applied:
      "The setup dialog asks tissue and results. The grid gate asks whether the circles are right. The orientation gate asks whether the rotations are right. Nothing else is asked anywhere.",
  },
  {
    number: "02",
    title: "One primary action",
    rule: "Exactly one filled button per view. Everything else is outlined, quiet, or a link.",
    applied:
      "Start. Grid is correct. Approve and finish. Stop here is always present and always quieter.",
  },
  {
    number: "03",
    title: "Never ask twice",
    rule: "If a person already saw the evidence and pressed the button, do not confirm it in a dialog.",
    applied:
      "Approving on the report sets the approval directly. The old modal is kept only for a run that reached that step with no gate open.",
  },
  {
    number: "04",
    title: "Collapsed by default",
    rule: "Summary first, detail one interaction away. Never open every branch on load.",
    applied:
      "126 core cards start filtered to what needs attention. Marker channel settings sit inside a closed disclosure marked most users can skip this.",
  },
  {
    number: "05",
    title: "Status is a word and a colour",
    rule: "Colour alone is never the carrier. Pair it with a label, an icon, or a number.",
    applied:
      "Cyan reads automatic, mint reads corrected, coral reads missing, and each core card prints the status in text above the image.",
  },
  {
    number: "06",
    title: "Measure, do not ask",
    rule: "If the data can answer it, the interface must not ask for it.",
    applied:
      "Rows, columns, core diameter, and array position are measured from the slide. None of them appear in any dialog.",
  },
  {
    number: "07",
    title: "Answer where you are",
    rule: "A person reviewing in the browser finishes in the browser. A person in QuPath finishes in QuPath.",
    applied:
      "Every gate offers the same two actions in both places, and either one continues the same run.",
  },
  {
    number: "08",
    title: "Shrink on every pass",
    rule: "A page that only ever grows becomes unreadable. Move detail down a level rather than adding a row.",
    applied:
      "The run summary dialog was 15 lines of text before every pass. It is now one headline inside the setup dialog.",
  },
];

const tokens = [
  { group: "Space", values: ["8", "16", "24", "32", "48", "64", "96"], note: "8 point grid. Gaps below 8 read as a mistake." },
  { group: "Radius", values: ["8", "12", "16", "20", "28", "999"], note: "999 is for pills only: buttons, chips, segmented controls." },
  { group: "Type", values: ["12", "14", "16", "21", "28", "40"], note: "16 is body. Nothing below 12. Line length stays near 60 characters." },
  { group: "Weight", values: ["400", "600", "700", "800"], note: "JavaFX drops any weight that is not a multiple of 100, silently." },
];

const components = [
  {
    name: "Waiting bar",
    when: "The run is blocked on this reviewer, and only then.",
    anatomy: "Kicker, headline, one line of counts, one quiet button, one filled button.",
    rules: [
      "Sticks under the header so it survives scrolling.",
      "Tinted with the warning colour while waiting, success after the answer is sent.",
      "Disappears completely when no gate is open, so a stale tab cannot advance a later run.",
    ],
  },
  {
    name: "Segmented choice",
    when: "A binary decision with a sensible default.",
    anatomy: "Two labels in one pill. The selected half is filled with primary blue.",
    rules: [
      "One option is always selected. Deselecting both is impossible.",
      "The label is the choice, not a description of the choice.",
      "A consequence line sits under the group, not inside the buttons.",
    ],
  },
  {
    name: "Core card",
    when: "One TMA position in the review grid.",
    anatomy: "Square image, status word, position label, then actions.",
    rules: [
      "The image is never cropped by the card, and never tinted.",
      "A four pixel top border carries the status colour behind the status word.",
      "Confirm is one click. Edit reveals the angle control in place.",
    ],
  },
  {
    name: "Setup dialog",
    when: "Once per run, before anything is processed.",
    anatomy: "Eyebrow, headline naming this run, one supporting line, two segmented rows, Cancel and Start.",
    rules: [
      "The headline changes with the state of the project, so it is never a generic title.",
      "No field can be typed into. Everything is a click.",
      "Cancel is the only way to change nothing, and it always exists.",
    ],
  },
];

const review = [
  "Can the reviewer say what this screen is asking within three seconds?",
  "Is there exactly one filled button?",
  "Does every status carry a word as well as a colour?",
  "Does anything ask for a value the slide already contains?",
  "Does the tissue image occupy more area than the interface around it?",
  "Does it work in light and dark, at 375 and at 1440 pixels wide?",
  "Was anything added without something else moving down a level?",
];

export default function Playbook() {
  return (
    <main className="prosePage">
      <a className="skipLink" href="#content">Skip to content</a>
      <SiteHeader />

      <section className="proseHero" id="content">
        <p className="kicker"><i className="ri-book-2-line" /> Design playbook</p>
        <h1>Eight laws, four components, one checklist.</h1>
        <p className="lead">
          The moodboard says how CoreAlign should feel. This page says how to build it and how
          to reject a screen that misses. Every law below is written against a real thing that
          went wrong in this project.
        </p>
        <div className="heroActions">
          <a className="button secondary" href={`${basePath}/moodboard/`}><i className="ri-palette-line" /> Moodboard</a>
          <a className="button secondary" href={`${basePath}/guide/`}><i className="ri-guide-line" /> Manual</a>
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Laws</p>
          <h2>What a CoreAlign screen must do.</h2>
        </div>
        <div className="lawList">
          {laws.map((law) => (
            <article key={law.number} className="lawRow">
              <span className="lawNumber">{law.number}</span>
              <div>
                <h3>{law.title}</h3>
                <p className="lawRule">{law.rule}</p>
                <p className="lawApplied"><b>In CoreAlign.</b> {law.applied}</p>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Tokens</p>
          <h2>The only numbers allowed.</h2>
        </div>
        <div className="tokenGrid">
          {tokens.map((token) => (
            <article key={token.group} className="tokenCard">
              <h3>{token.group}</h3>
              <div className="tokenChips">
                {token.values.map((value) => <code key={value}>{value}</code>)}
              </div>
              <p>{token.note}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Components</p>
          <h2>Four parts carry the whole interface.</h2>
        </div>
        <div className="componentGrid">
          {components.map((component) => (
            <article key={component.name} className="componentCard">
              <h3>{component.name}</h3>
              <dl>
                <div><dt>Use it when</dt><dd>{component.when}</dd></div>
                <div><dt>Anatomy</dt><dd>{component.anatomy}</dd></div>
              </dl>
              <ul>
                {component.rules.map((rule) => <li key={rule}>{rule}</li>)}
              </ul>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Accessibility</p>
          <h2>Non negotiable.</h2>
        </div>
        <div className="a11yGrid">
          <article><i className="ri-contrast-2-line" /><h3>Contrast</h3><p>Body text meets 4.5 to 1 in both themes. Status text is checked against its own tinted background, not against white.</p></article>
          <article><i className="ri-cursor-line" /><h3>Target size</h3><p>Every control is at least 44 pixels tall. Gate buttons are 48, because pressing the wrong one costs a run.</p></article>
          <article><i className="ri-keyboard-line" /><h3>Keyboard</h3><p>The primary button is the default button. Focus rings are three pixels and never removed.</p></article>
          <article><i className="ri-pause-circle-line" /><h3>Motion</h3><p>Transitions are transform and opacity only, and reduce to nothing under prefers reduced motion.</p></article>
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Before you ship</p>
          <h2>Seven questions. One no is enough to stop.</h2>
        </div>
        <ol className="checkList">
          {review.map((question) => <li key={question}>{question}</li>)}
        </ol>
      </section>

      <section className="startCta">
        <div><p className="eyebrow">See it working</p><h2>The manual walks the same rules end to end.</h2></div>
        <a className="button" href={`${basePath}/guide/`}>Open the manual <i className="ri-arrow-right-line" /></a>
      </section>

      <footer>
        <a className="siteBrand" href={`${basePath}/`}><span className="brandIcon"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <p>Research software for TMA image preparation. Review results before clinical use.</p>
        <div>
          <a href={`${basePath}/moodboard/`}>Moodboard</a>
          <a href={`${basePath}/guide/`}>Manual</a>
          <a href={`${basePath}/docs/`}>Reference</a>
          <a href="https://github.com/hengkp/corealign-tma">GitHub</a>
        </div>
      </footer>
    </main>
  );
}
