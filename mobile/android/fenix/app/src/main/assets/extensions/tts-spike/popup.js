/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/* global browser */

/**
 * SPIKE: on-device "Listen to page" via the Firefox AI Runtime.
 *
 * text -> browser.trial.ml (SpeechT5) -> {audio, sampling_rate} -> Web Audio.
 * `browser.trial.ml` wraps the toolkit ML runtime (createEngine); nothing here
 * plays audio for us, so we build the AudioBuffer + BufferSource ourselves.
 *
 * Model is SpeechT5 for now; swap MODEL to try a different one later.
 */

// Only Mozilla/Xenova HF orgs are allowed by the trial.ml model allow-list.
const MODEL = {
  create: {
    modelHub: "huggingface",
    taskName: "text-to-speech",
    modelId: "Xenova/mms-tts-eng",
    dtype: "q8",
  },
  // VITS / MMS-TTS is self-contained: no separate vocoder, no speaker embeddings.
  run: {},
  // Known output rate. Lets us open the AudioContext up front, while the click
  // still counts as user activation, instead of waiting on the first synthesis.
  outputSampleRate: 16000,
};

// Chunks kept in flight ahead of the playhead, so a chunk that synthesizes
// slower than real time is covered by audio that is already queued.
const PREFETCH = 3;
// Cap on synthesized-but-unplayed audio, so a long page isn't synthesized in
// its entirety up front.
const MAX_QUEUED_SECONDS = 10;
// Headroom between scheduling a chunk and the time it starts.
const START_LEAD_SECONDS = 0.15;

// Upper bound on a chunk, so one unpunctuated block can't become a multi-second
// synthesis; the opening chunk is held shorter still to get audio out quickly.
const MAX_CHUNK_CHARS = 200;
const FIRST_CHUNK_CHARS = 80;
// Below this a chunk is too short to be worth splitting off on its own.
const MIN_CHUNK_CHARS = 20;
const CLAUSE_MARKS = [", ", "; ", ": "];
// Anything the English model can actually voice. Latin letters and digits only:
// its vocab holds no punctuation and no non-Latin script, and the tokenizer
// deletes — rather than substitutes — everything it doesn't recognise.
const SPEAKABLE = /[a-z0-9]/i;

const logEl = document.getElementById("log");
function log(msg) {
  logEl.textContent += `${msg}\n`;
  logEl.scrollTop = logEl.scrollHeight;
}

let engineReady = false;
let stopped = false;
let audioCtx = null;
let queuedSources = [];
let nextStartTime = 0;

browser.trial.ml.onProgress.addListener(data => {
  const pct =
    typeof data?.progress === "number" ? ` ${Math.round(data.progress)}%` : "";
  if (data?.statusText || pct) {
    log(`[model] ${data.statusText || "downloading"}${pct}`);
  }
});

// trialML is pre-granted for this built-in via addOptionalPermissions, but
// request it defensively in case that hasn't propagated.
async function ensurePermission() {
  if (await browser.permissions.contains({ permissions: ["trialML"] })) {
    return;
  }
  const granted = await browser.permissions.request({
    permissions: ["trialML"],
  });
  if (!granted) {
    throw new Error("trialML permission was not granted");
  }
}

async function ensureEngine() {
  if (engineReady) {
    return;
  }
  log("Creating engine (first run downloads the model)...");
  await browser.trial.ml.createEngine(MODEL.create);
  engineReady = true;
  log("Engine ready.");
}

async function synthesize(text) {
  const t0 = performance.now();
  const res = await browser.trial.ml.runEngine({
    args: [text],
    options: MODEL.run,
  });
  const audio = res?.audio ?? res?.output?.audio;
  const sampleRate = res?.sampling_rate ?? res?.output?.sampling_rate;
  if (!audio || typeof sampleRate !== "number") {
    throw new Error(
      `Unexpected TTS result shape: ${JSON.stringify(Object.keys(res || {}))}`
    );
  }
  const samples =
    audio instanceof Float32Array ? audio : Float32Array.from(audio);

  // Diagnostics (surface in logcat via GeckoViewConsole) to tell a rate bug
  // apart from just-mediocre SpeechT5 output.
  let peak = 0;
  let sumSq = 0;
  for (let i = 0; i < samples.length; i++) {
    const v = samples[i];
    const a = v < 0 ? -v : v;
    if (a > peak) {
      peak = a;
    }
    sumSq += v * v;
  }
  const rms = Math.sqrt(sumSq / (samples.length || 1));
  const synthMs = performance.now() - t0;
  const duration = samples.length / sampleRate;
  console.warn(
    `[tts-spike] synth isF32=${audio instanceof Float32Array} ` +
      `n=${samples.length} sr=${sampleRate} ` +
      `dur=${duration.toFixed(2)}s ` +
      `synth=${Math.round(synthMs)}ms ` +
      `rtf=${duration ? (synthMs / 1000 / duration).toFixed(2) : "n/a"} ` +
      `peak=${peak.toFixed(3)} rms=${rms.toFixed(4)}`
  );

  return { samples, sampleRate };
}

/**
 * One long-lived context for the whole session. A context per chunk costs an
 * audio-device open/close at every chunk boundary (hundreds of ms on Android,
 * more over Bluetooth) and re-enters autoplay gating once the click's user
 * activation has expired. Driven at the model's rate so nothing is resampled.
 */
function ensureAudioContext() {
  if (audioCtx && audioCtx.state !== "closed") {
    return audioCtx;
  }
  const sampleRate = MODEL.outputSampleRate;
  try {
    audioCtx = new AudioContext({ sampleRate });
  } catch (e) {
    audioCtx = new AudioContext();
  }
  console.warn(
    `[tts-spike] audioctx requested sr=${sampleRate} actual=${audioCtx.sampleRate}`
  );
  return audioCtx;
}

/**
 * Appends a chunk to the context's timeline, butted up against whatever is
 * already queued so consecutive chunks play without a seam.
 *
 * @param {AudioContext} ctx - The shared output context.
 * @param {Float32Array} samples - Mono PCM for this chunk.
 * @param {number} sampleRate - Rate the samples were synthesized at.
 * @returns {AudioBufferSourceNode} The scheduled source.
 */
function scheduleChunk(ctx, samples, sampleRate) {
  const buffer = ctx.createBuffer(1, samples.length, sampleRate);
  buffer.getChannelData(0).set(samples);
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);

  const earliest = ctx.currentTime + START_LEAD_SECONDS;
  if (nextStartTime && nextStartTime < earliest) {
    log(
      `Underrun: synthesis fell ${(earliest - nextStartTime).toFixed(1)}s behind.`
    );
  }
  const startAt = Math.max(earliest, nextStartTime);
  source.start(startAt);
  nextStartTime = startAt + buffer.duration;

  queuedSources.push(source);
  source.onended = () => {
    source.disconnect();
    queuedSources = queuedSources.filter(s => s !== source);
  };
  return source;
}

function stopPlayback() {
  for (const source of queuedSources) {
    source.onended = null;
    try {
      source.stop();
    } catch (e) {}
    source.disconnect();
  }
  queuedSources = [];
  nextStartTime = 0;
}

async function suspendPlayback() {
  if (audioCtx && audioCtx.state === "running") {
    await audioCtx.suspend().catch(() => {});
  }
}

/**
 * Splits `text` at the last clause boundary inside `text.slice(0, limit)`.
 *
 * @param {string} text - Text to look in.
 * @param {number} limit - How far into the text to consider.
 * @returns {number} Index just past the boundary, or -1 if there isn't one.
 */
function findClauseBreak(text, limit) {
  const window = text.slice(0, limit);
  let cut = -1;
  for (const mark of CLAUSE_MARKS) {
    cut = Math.max(cut, window.lastIndexOf(mark));
  }
  return cut < 0 ? -1 : cut + 1;
}

/**
 * Breaks a single over-long run of text down to `MAX_CHUNK_CHARS`, preferring
 * clause boundaries, then word boundaries, then a hard cut.
 *
 * @param {string} text - One sentence or unpunctuated block.
 * @returns {string[]} Chunks, each within the cap.
 */
function capChunkLength(text) {
  const chunks = [];
  let rest = text;
  while (rest.length > MAX_CHUNK_CHARS) {
    let cut = findClauseBreak(rest, MAX_CHUNK_CHARS);
    if (cut < MIN_CHUNK_CHARS) {
      cut = rest.slice(0, MAX_CHUNK_CHARS).lastIndexOf(" ");
    }
    if (cut < MIN_CHUNK_CHARS) {
      cut = MAX_CHUNK_CHARS;
    }
    chunks.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) {
    chunks.push(rest);
  }
  return chunks;
}

/**
 * Time-to-first-audio scales with the length of the opening chunk, so shorten
 * it when a clause boundary allows. Only at a real boundary: cutting at an
 * arbitrary word makes the model close the utterance with falling intonation
 * mid-sentence, which is audible now that chunks play back-to-back.
 *
 * @param {string} chunk - The first chunk.
 * @returns {string[]} The chunk, split in two if it was worth doing.
 */
function shortenFirstChunk(chunk) {
  if (chunk.length <= FIRST_CHUNK_CHARS) {
    return [chunk];
  }
  const cut = findClauseBreak(chunk, FIRST_CHUNK_CHARS);
  if (cut < MIN_CHUNK_CHARS) {
    return [chunk];
  }
  return [chunk.slice(0, cut).trim(), chunk.slice(cut).trim()];
}

function chunkText(text) {
  const chunks = [];
  // Split on block boundaries first: headings, list items and nav links carry
  // no terminal punctuation, so sentence-splitting `innerText` on its own
  // collapses a page's whole preamble into one enormous chunk.
  for (const block of text.split(/\n+/)) {
    for (const sentence of block.split(/(?<=[.!?])\s+/)) {
      const trimmed = sentence.trim();
      if (trimmed) {
        chunks.push(...capChunkLength(trimmed));
      }
    }
  }
  // The model's tokenizer deletes every character outside its vocab, so a
  // chunk with nothing in that vocab normalizes to "" and synthesizes to a
  // zero-length waveform. Drop those here rather than paying for the inference.
  const speakable = chunks.filter(chunk => SPEAKABLE.test(chunk));
  if (speakable.length) {
    speakable.unshift(...shortenFirstChunk(speakable.shift()));
  }
  return speakable;
}

async function speak(text) {
  const chunks = chunkText(text);
  if (!chunks.length) {
    return;
  }

  const ctx = ensureAudioContext();
  await ctx.resume();
  nextStartTime = 0;

  // The engine runs inference serially, so several outstanding requests only
  // keep it saturated; the win is that playback never waits on a round trip.
  const inFlight = [];
  let issued = 0;
  const topUp = () => {
    while (inFlight.length < PREFETCH && issued < chunks.length) {
      const index = issued++;
      // Resolve rather than reject: one bad chunk shouldn't abandon the rest of
      // the page, and this also keeps a queued-but-not-yet-awaited failure from
      // surfacing as an unhandled rejection.
      inFlight.push(
        synthesize(chunks[index]).catch(error => {
          log(`Skipped chunk ${index + 1}: ${error.message}`);
          return null;
        })
      );
    }
  };
  topUp();

  let last = null;
  for (let i = 0; i < chunks.length && !stopped; i++) {
    const current = await inFlight.shift();
    topUp();
    if (stopped) {
      break;
    }
    if (!current) {
      continue;
    }
    // A zero-length buffer throws in createBuffer, so never schedule one.
    if (!current.samples.length) {
      log(`Chunk ${i + 1} produced no audio; skipping.`);
      continue;
    }
    last = scheduleChunk(ctx, current.samples, current.sampleRate);
    log(
      `Queued ${i + 1}/${chunks.length} ` +
        `(${(current.samples.length / current.sampleRate).toFixed(1)}s)`
    );
    while (!stopped && nextStartTime - ctx.currentTime > MAX_QUEUED_SECONDS) {
      await new Promise(resolve => setTimeout(resolve, 250));
    }
  }

  if (last && !stopped) {
    await new Promise(resolve =>
      last.addEventListener("ended", resolve, { once: true })
    );
  }
}

document.getElementById("speak").addEventListener("click", async () => {
  stopped = false;
  stopPlayback();
  const text = document.getElementById("text").value;
  try {
    await ensurePermission();
    await ensureEngine();
    const t0 = performance.now();
    await speak(text);
    log(`Done in ${Math.round(performance.now() - t0)} ms.`);
  } catch (e) {
    log(`ERROR: ${e.message}`);
  } finally {
    // Suspended, not closed: the next Speak reuses the same output stream.
    await suspendPlayback();
  }
});

document.getElementById("stop").addEventListener("click", async () => {
  stopped = true;
  stopPlayback();
  await suspendPlayback();
  log("Stopped.");
});

document.getElementById("use-page").addEventListener("click", async () => {
  try {
    const results = await browser.tabs.executeScript({
      code: "document.body ? document.body.innerText.slice(0, 4000) : ''",
    });
    const pageText = (results && results[0]) || "";
    if (pageText.trim()) {
      document.getElementById("text").value = pageText.trim();
      log(`Loaded ${pageText.length} chars from the current page.`);
    } else {
      log("No readable text found on the current page.");
    }
  } catch (e) {
    log(`Could not read page: ${e.message}`);
  }
});
