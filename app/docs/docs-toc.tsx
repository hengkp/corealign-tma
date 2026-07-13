"use client";

import { useEffect, useState } from "react";

type TocItem = readonly [string, string];

export default function DocsToc({ toc, release }: { toc: readonly TocItem[]; release: string }) {
  const [active, setActive] = useState(toc[0]?.[0] ?? "overview");

  useEffect(() => {
    const sections = toc
      .map(([id]) => document.getElementById(id))
      .filter((section): section is HTMLElement => Boolean(section));
    const updateActive = () => {
      const readingLine = 130;
      let current = sections[0]?.id ?? "overview";
      for (const section of sections) {
        if (section.getBoundingClientRect().top <= readingLine) current = section.id;
        else break;
      }
      if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 8) {
        current = sections.at(-1)?.id ?? current;
      }
      setActive(current);
    };
    updateActive();
    window.addEventListener("scroll", updateActive, { passive: true });
    window.addEventListener("resize", updateActive);
    window.addEventListener("hashchange", updateActive);
    return () => {
      window.removeEventListener("scroll", updateActive);
      window.removeEventListener("resize", updateActive);
      window.removeEventListener("hashchange", updateActive);
    };
  }, [toc]);

  return (
    <aside className="docsToc" aria-label="Documentation table of contents">
      <p>CoreAlign guide</p>
      <nav>
        {toc.map(([id, label]) => (
          <a
            href={`#${id}`}
            key={id}
            className={active === id ? "active" : undefined}
            aria-current={active === id ? "location" : undefined}
            onClick={() => setActive(id)}
          >
            {label}
          </a>
        ))}
      </nav>
      <a className="tocDownload" href={release}><i className="ri-download-2-line" /> Download</a>
    </aside>
  );
}
