// Popup UI (P1-C.6): today's top sites by time, a pause toggle, and the URL
// privacy toggle. Reads today's chrome events from the daemon's loopback
// timeline; degrades to a friendly message when the daemon isn't running.

import type { Event } from "../shared/contract.js";
import { getSettings, setSettings, type UrlPrivacy } from "../shared/settings.js";
import { categorize } from "../background/categorize.js";
import { formatDuration, topSites } from "./sites.js";

const pauseBtn = document.getElementById("pause") as HTMLButtonElement;
const privacySel = document.getElementById("privacy") as HTMLSelectElement;
const siteList = document.getElementById("site-list") as HTMLUListElement;
const statusEl = document.getElementById("status") as HTMLParagraphElement;

/** GET today's events [local-midnight, now) from the daemon. */
async function fetchTodayEvents(port: number): Promise<Event[]> {
  const now = new Date();
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const qs = `from=${encodeURIComponent(start.toISOString())}&to=${encodeURIComponent(now.toISOString())}`;
  const res = await fetch(`http://127.0.0.1:${port}/timeline?${qs}`);
  if (!res.ok) throw new Error(`daemon responded ${res.status}`);
  return (await res.json()) as Event[];
}

function renderSites(events: Event[]): void {
  const sites = topSites(events);
  siteList.replaceChildren();
  if (sites.length === 0) {
    statusEl.textContent = "No browser activity tracked yet today.";
    return;
  }
  statusEl.textContent = "";
  for (const site of sites) {
    const li = document.createElement("li");

    const left = document.createElement("span");
    left.className = "domain";
    left.textContent = site.domain;
    const cat = categorize(site.domain);
    if (cat) {
      const tag = document.createElement("span");
      tag.className = "cat";
      tag.textContent = ` · ${cat.replace("_", " ")}`;
      left.appendChild(tag);
    }

    const time = document.createElement("span");
    time.className = "time";
    time.textContent = formatDuration(site.durationMs);

    li.append(left, time);
    siteList.appendChild(li);
  }
}

async function loadSites(port: number): Promise<void> {
  try {
    const events = await fetchTodayEvents(port);
    renderSites(events);
  } catch {
    siteList.replaceChildren();
    statusEl.textContent =
      "Daemon offline — tracking continues locally and will sync when it's back.";
  }
}

function reflectPause(paused: boolean): void {
  pauseBtn.textContent = paused ? "Resume tracking" : "Pause tracking";
  pauseBtn.dataset["paused"] = String(paused);
}

async function init(): Promise<void> {
  const settings = await getSettings();
  reflectPause(settings.paused);
  privacySel.value = settings.urlPrivacy;

  pauseBtn.addEventListener("click", async () => {
    const next = await getSettings();
    const updated = await setSettings({ paused: !next.paused });
    reflectPause(updated.paused);
  });

  privacySel.addEventListener("change", async () => {
    await setSettings({ urlPrivacy: privacySel.value as UrlPrivacy });
  });

  await loadSites(settings.agentPort);
}

void init();
