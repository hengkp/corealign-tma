"use client";

import { useEffect, useState } from "react";

type TocItem = readonly [string, string];

export default function DocsToc({ toc, release }: { toc: readonly TocItem[]; release: string }) {
  const [active, setActive] = useState(toc[0]?.[0] ?? "overview");

  useEffect(() => {
    const sections = toc
      .map(([id]) => document.getElementById(id))
      .filter((section): section is HTMLElement => Boolean(section));
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible[0]?.target.id) setActive(visible[0].target.id);
      },
      { rootMargin: "-96px 0px -68% 0px", threshold: [0, 0.05, 0.25] },
    );
    sections.forEach((section) => observer.observe(section));
    return () => observer.disconnect();
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
