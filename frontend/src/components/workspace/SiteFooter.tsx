"use client";

import { useSyncExternalStore } from "react";
import { siteFooterText, SUBMISSION_END } from "@/lib/siteFooter";

function subscribe(onChange: () => void) {
  // Refresh after suspended tabs, at year changes, and at the submission cutoff.
  let timer: number;
  const schedule = () => {
    const remaining = SUBMISSION_END - Date.now();
    timer = window.setTimeout(() => { onChange(); schedule(); },
      remaining > 0 ? Math.min(remaining, 60_000) : 60_000);
  };
  schedule();
  document.addEventListener("visibilitychange", onChange);
  return () => {
    window.clearTimeout(timer);
    document.removeEventListener("visibilitychange", onChange);
  };
}

export function SiteFooter() {
  const text = useSyncExternalStore(subscribe, () => siteFooterText(Date.now()), () => "");
  return <footer className="workspace-footer submission-footer"><span>{text}</span></footer>;
}
