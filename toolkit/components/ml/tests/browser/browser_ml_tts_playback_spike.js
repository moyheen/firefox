/* Any copyright is dedicated to the Public Domain.
   https://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

/**
 * SPIKE: "Listen to page" (Option B) — audio out of the Gecko ML runtime.
 *
 * Goal: prove the produce->play pipeline end to end — generate speech with the
 * ML runtime's `text-to-speech` task and play the returned waveform back with
 * the Web Audio API. The runtime returns a raw `{ audio, sampling_rate }`
 * waveform today but nothing in Firefox plays it; this fills that gap.
 *
 * The model is intentionally SpeechT5 (English, already in the vendored
 * transformers.js) just to validate the path. Swap TTS_MODEL below to try a
 * different model in the future (e.g. Kokoro / Supertonic) with no other
 * changes to the pipeline functions.
 *
 * This talks to the real HuggingFace hub, so it is gated behind an env var to
 * stay a no-op in CI. Run it locally (downloads ~q8 SpeechT5 + vocoder on the
 * first run):
 *   MOZ_TTS_SPIKE=1 ./mach test \
 *     toolkit/components/ml/tests/browser/browser_ml_tts_playback_spike.js --headless
 */

requestLongerTimeout(10);

/** This spike hits the network; only run it when explicitly requested. */
function spikeEnabled() {
  if (!Services.env.get("MOZ_TTS_SPIKE")) {
    info("Set MOZ_TTS_SPIKE=1 to run this networked TTS spike; skipping.");
    Assert.ok(true, "spike skipped (set MOZ_TTS_SPIKE=1 to run)");
    return false;
  }
  return true;
}

const HF = "https://huggingface.co";

// Everything model-specific lives here. To evaluate another model, change this
// object only; the pipeline (synthesize/playSamples/speak) is model-agnostic.
const TTS_MODEL = {
  pipeline: {
    taskName: "text-to-speech",
    modelId: "Xenova/speecht5_tts",
    modelHubUrlTemplate: "{model}/resolve/{revision}",
    modelRevision: "main",
    dtype: "q8",
    timeoutMS: 2 * 60 * 1000,
  },
  // SpeechT5 needs a separate vocoder + a speaker-embedding vector. Character-
  // level models (Supertonic) and VITS need neither, so this shrinks/vanishes
  // for a future model.
  runOptions: {
    speaker_embeddings: `${HF}/Xenova/transformers.js-docs/resolve/main/speaker_embeddings.bin`,
    vocoder: `${HF}/Xenova/speecht5_hifigan`,
  },
};

/**
 * Instantiate a TTS engine in the ML runtime's inference process.
 *
 * @returns {Promise<object>} the MLEngine handle (has `.run`).
 */
async function createTtsEngine() {
  return createEngine(new PipelineOptions(TTS_MODEL.pipeline));
}

/**
 * Run one synthesis request and normalize the result to raw PCM.
 *
 * @param {object} engine - engine from createTtsEngine().
 * @param {string} text - text to speak.
 * @returns {Promise<{samples: Float32Array, sampleRate: number}>}
 */
async function synthesize(engine, text) {
  const result = await engine.run({
    args: [text],
    options: TTS_MODEL.runOptions,
  });

  // Generic ONNX pipeline returns the transformers.js output object directly
  // ({ audio, sampling_rate }); guard the shape rather than assume it.
  const audio = result?.audio ?? result?.output?.audio;
  const sampleRate = result?.sampling_rate ?? result?.output?.sampling_rate;

  Assert.ok(audio, "TTS result exposes an audio waveform");
  Assert.equal(typeof sampleRate, "number", "TTS result exposes a sample rate");

  const samples =
    audio instanceof Float32Array ? audio : Float32Array.from(audio);
  return { samples, sampleRate };
}

/** Root-mean-square amplitude — used to assert the audio isn't silence. */
function rms(samples) {
  let sum = 0;
  for (let i = 0; i < samples.length; i++) {
    sum += samples[i] * samples[i];
  }
  return Math.sqrt(sum / samples.length);
}

/**
 * Play a PCM buffer through the Web Audio API in the given window.
 *
 * @param {Window} win - a DOM window whose AudioContext to use.
 * @param {Float32Array} samples - mono PCM.
 * @param {number} sampleRate - Hz.
 * @returns {Promise<void>} resolves when playback ends.
 */
async function playSamples(win, samples, sampleRate) {
  const ctx = new win.AudioContext();
  try {
    const buffer = ctx.createBuffer(1, samples.length, sampleRate);
    buffer.getChannelData(0).set(samples);

    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);

    // AudioContext can start suspended under the autoplay policy.
    await ctx.resume();

    const ended = new Promise(resolve => {
      source.onended = resolve;
    });
    source.start();
    await ended;
  } finally {
    await ctx.close();
  }
}

/** Naive sentence splitter — enough to chunk an article for streaming. */
function splitIntoSentences(text) {
  return text
    .split(/(?<=[.!?])\s+/)
    .map(s => s.trim())
    .filter(Boolean);
}

/**
 * Read a whole passage: synthesize sentence by sentence and play in order,
 * synthesizing the next sentence while the current one plays (1-ahead
 * prefetch) so playback is continuous.
 *
 * @param {object} engine
 * @param {Window} win
 * @param {string} text
 */
async function speak(engine, win, text) {
  const sentences = splitIntoSentences(text);
  let next = synthesize(engine, sentences[0]);

  for (let i = 0; i < sentences.length; i++) {
    const current = await next;
    if (i + 1 < sentences.length) {
      next = synthesize(engine, sentences[i + 1]);
    }
    Assert.greater(
      rms(current.samples),
      1e-4,
      `Sentence ${i + 1}/${sentences.length} produced non-silent audio`
    );
    await playSamples(win, current.samples, current.sampleRate);
  }
}

async function setup() {
  Services.env.set("MOZ_ALLOW_EXTERNAL_ML_HUB", "true");
  await SpecialPowers.pushPrefEnv({
    set: [
      ["browser.ml.enable", true],
      ["browser.ml.logLevel", "Error"],
      ["browser.ml.modelHubRootUrl", HF],
      // Let Web Audio play without a user gesture in the test.
      ["media.autoplay.default", 0],
      ["media.autoplay.blocking_policy", 0],
    ],
  });
}

// Core proof: one sentence in -> non-silent PCM out -> Web Audio playback runs
// to completion.
add_task(async function test_synthesize_and_play() {
  if (!spikeEnabled()) {
    return;
  }
  await setup();
  const engine = await createTtsEngine();
  try {
    const { samples, sampleRate } = await synthesize(
      engine,
      "The one ring to rule them all."
    );

    Assert.greater(samples.length, 0, "Got PCM samples back");
    Assert.greater(sampleRate, 0, `Sample rate is positive (${sampleRate} Hz)`);
    Assert.greater(rms(samples), 1e-4, "Synthesized audio is not silence");

    info(
      `Synthesized ${samples.length} samples @ ${sampleRate}Hz ` +
        `(${(samples.length / sampleRate).toFixed(2)}s), RMS=${rms(samples).toFixed(4)}`
    );

    await playSamples(window, samples, sampleRate);
    Assert.ok(true, "Web Audio playback completed");
  } finally {
    await EngineProcess.destroyMLEngine();
  }
});

// Exercises the chunked read-a-passage flow with prefetch.
add_task(async function test_speak_passage() {
  if (!spikeEnabled()) {
    return;
  }
  await setup();
  const engine = await createTtsEngine();
  try {
    await speak(
      engine,
      window,
      "Firefox can read this page aloud. The audio never leaves your device. " +
        "This is the second sentence."
    );
    Assert.ok(true, "Spoke a multi-sentence passage end to end");
  } finally {
    await EngineProcess.destroyMLEngine();
  }
});
