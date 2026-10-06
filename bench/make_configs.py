"""Generate the bench-config.json files the app runs (see BenchRunner.kt).

    py bench/make_configs.py threads            -> bench/threads.json   (thread sweep, small models)
    py bench/make_configs.py ladder --threads N -> bench/ladder.json    (frozen settings, all rungs)
"""
import argparse
import json
from pathlib import Path

HERE = Path(__file__).parent
PROMPTS = json.loads((HERE / "prompts.json").read_text(encoding="utf-8"))
LADDER = ["Qwen3-0.6B-Q4_K_M.gguf", "Qwen3-1.7B-Q4_K_M.gguf", "Qwen3-4B-Q4_K_M.gguf", "Qwen3-8B-Q4_K_M.gguf"]

# Fixed for every rung (docs/criteria.md).
DEFAULTS = {
    "n_ctx": 2048, "n_batch": 512, "n_predict": 128, "ignore_eos": True, "temp": 0.0, "seed": 42,
    "chat_template": True, "reps": 3, "warmup": True, "prompts": ["short", "long"], "cooldown_s": 60,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["threads", "ladder"])
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--session")
    a = ap.parse_args()

    if a.kind == "threads":
        jobs = [{"model": m, "threads": t, "tag": "threads", "cooldown_s": 30}
                for m in LADDER[:2] for t in (2, 4, 6, 8)]
        cfg = {"session": a.session or "threads-1", "prompts": PROMPTS,
               "defaults": dict(DEFAULTS, tag="threads"), "jobs": jobs}
    else:
        cfg = {"session": a.session or "ladder-1", "prompts": PROMPTS,
               "defaults": dict(DEFAULTS, threads=a.threads, tag="ladder"),
               "jobs": [{"model": m} for m in LADDER]}

    out = HERE / f"{a.kind}.json"
    out.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {out} ({len(cfg['jobs'])} jobs)")


if __name__ == "__main__":
    main()
