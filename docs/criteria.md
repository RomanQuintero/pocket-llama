# Phase 4 — "Practically interactive", defined before the ladder was measured

Written 2026-10-06, after the single Phase 1 smoke run (Qwen3-0.6B) and **before** any ladder rung
(1.7B / 4B / 8B) was benchmarked. Thresholds are not to be changed after seeing the results; the raw
measurements are kept so readers can apply their own.

## Levels (each implies the previous one)

| Level | Criterion |
|---|---|
| **FITS** | The model loads (`llama_model_load_from_file` + context creation succeed) and completes at least one generation without the process being killed. |
| **USABLE** | All measured repetitions complete with no crash/OOM/LMK kill, **and** median decode ≥ **5 tok/s**, **and** median time-to-first-token for the long prompt (~250 tokens) ≤ **10 s**. |
| **INTERACTIVE** | USABLE, **and** median decode ≥ **10 tok/s**, **and** median TTFT for the short chat prompt ≤ **2 s**. |

## Why these numbers

- **Reading speed.** Adults read silent English at roughly 200–300 words/min (≈ 3.3–5 words/s).
  With ~1.3 tokens per English word that is ≈ 4.5–6.5 tok/s. Below ~5 tok/s the text appears slower than
  it is read, so the user is waiting on the model: that is the floor for USABLE.
- **Comfortable chat.** ~10 tok/s is about twice reading speed, so text streams ahead of the reader.
  It is a commonly used rule of thumb for local LLM chat being "pleasant". This is a convention, not a
  law; it is stated so the line is fixed in advance.
- **Latency.** Classic response-time guidance (Nielsen / Miller): ~1 s keeps the flow of thought,
  ~10 s is the limit of attention. A chat prompt should start answering within ~2 s to feel interactive;
  a long prompt (pasted paragraph) may take up to ~10 s before it becomes unusable.

## Measurement rules attached to the criteria

- Decode tok/s = generated forward passes / time inside those `llama_decode` calls (prefill excluded).
- TTFT = prefill time + first sample, measured from the start of prefill (model already loaded).
- Model load time is reported separately and is **not** part of USABLE/INTERACTIVE (it is paid once).
- Fixed settings for every rung: same prompts, 128 generated tokens with EOS ignored, greedy sampling,
  n_ctx 2048, n_batch 512, same thread count (chosen once by a sweep on the small models, then frozen),
  1 warm-up + 3 measured repetitions, 60 s cooldown between models, run order and thermal state recorded.
- Medians over the 3 repetitions are used for the classification.

## Addendum (written after the ladder, before the cold control was run)

The ladder ran in fixed order 0.6B → 1.7B → 4B → 8B, so the 8B was measured with the phone already at
thermal status 3 (big cores capped near 1 GHz). To avoid silently penalising it (Phase 5), the 8B is
re-run alone from a cold start (thermal status 0, battery ≤ 32.5 °C): 1 warm-up + 6 repetitions with the
same settings. **Its classification uses the median of the first 3 repetitions** (same count as the
ladder); all 6 are kept as a thermal-decay series. Both the in-ladder and the cold numbers are reported.
