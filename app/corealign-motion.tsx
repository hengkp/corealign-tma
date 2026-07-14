"use client";

import { useEffect, useRef } from "react";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export default function CoreAlignMotion() {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!container.current || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    let disposed = false;
    let animation: { destroy: () => void } | undefined;

    async function start() {
      const [{ default: lottie }, response] = await Promise.all([
        import("lottie-web/build/player/lottie_light"),
        fetch(`${basePath}/animations/corealign-flow.json`),
      ]);
      if (!response.ok || disposed || !container.current) return;
      const animationData = await response.json();
      if (disposed || !container.current) return;
      animation = lottie.loadAnimation({
        container: container.current,
        renderer: "svg",
        loop: true,
        autoplay: true,
        animationData,
        rendererSettings: { progressiveLoad: true },
      });
    }

    void start();
    return () => {
      disposed = true;
      animation?.destroy();
    };
  }, []);

  return <div ref={container} className="lottieFlow" aria-hidden="true" />;
}
