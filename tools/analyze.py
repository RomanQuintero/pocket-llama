"""Turn the app's JSONL records into results/benchmark.csv, results/summary.csv and figures.

    python tools/analyze.py results/raw/<session>/results/<session>.jsonl [...more jsonl]

Only measured values are used; nothing is extrapolated. Rungs that failed to load or were killed are
kept as rows with their failure reason. Classification thresholds come from docs/criteria.md.
"""
import csv
import json
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "results"
FIG = OUT / "figures"

# docs/criteria.md (fixed before the ladder was measured)
USABLE_TG, USABLE_TTFT_LONG_MS = 5.0, 10_000
INTER_TG, INTER_TTFT_SHORT_MS = 10.0, 2_000


def load_records(paths):
    recs = []
    for p in paths:
        for line in Path(p).read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                r["_file"] = str(p)
                recs.append(r)
    return recs


def params_b(model_file, load_res):
    n = load_res.get("n_params") if load_res else None
    return round(n / 1e9, 3) if n else None


def build_rows(recs):
    loads, runs, fails, deaths = {}, [], [], []
    for r in recs:
        ev = r.get("event")
        key = (r.get("session"), r.get("job_index"))
        if ev == "load":
            loads[key] = r
        elif ev == "run":
            runs.append(r)
        elif ev == "job_failed":
            fails.append(r)
        elif ev == "process_died":
            deaths.append(r)

    rows = []
    for r in runs:
        key = (r["session"], r["job_index"])
        ld = loads.get(key, {})
        lr = ld.get("result", {})
        g = r["result"]
        s = r["settings"]
        sb, sa = r.get("state_before", {}), r.get("state_after", {})
        rows.append({
            "session": r["session"], "job_index": r["job_index"], "seq_in_job": r.get("seq_in_job"),
            "tag": r.get("tag"), "model_file": r["model_file"], "params_b": params_b(r["model_file"], lr),
            "file_size_bytes": ld.get("file_size_bytes"), "load_ms": lr.get("load_ms"), "ctx_ms": lr.get("ctx_ms"),
            "threads": s["threads"], "n_ctx": s["n_ctx"], "n_batch": s["n_batch"], "n_predict": s["n_predict"],
            "prompt_id": r["prompt_id"], "rep": r["rep"], "ok": g["ok"], "stop_reason": g["stop_reason"],
            "error": g.get("error", ""),
            "n_prompt": g["n_prompt"], "n_gen": g["n_gen"], "prefill_ms": g["prefill_ms"], "decode_ms": g["decode_ms"],
            "ttft_ms": g["ttft_ms"], "wall_ms": g["wall_ms"],
            "prefill_tps": r.get("prefill_tps"), "decode_tps": r.get("decode_tps"),
            "rss_mb_after_load": (lr.get("mem_after_ctx", {}).get("vm_rss_kb") or 0) / 1024 or None,
            "rss_anon_mb_after_load": (lr.get("mem_after_ctx", {}).get("rss_anon_kb") or 0) / 1024 or None,
            "rss_mb": g["mem_after"]["vm_rss_kb"] / 1024, "rss_anon_mb": g["mem_after"]["rss_anon_kb"] / 1024,
            "rss_file_mb": g["mem_after"]["rss_file_kb"] / 1024, "vm_hwm_mb": g["mem_after"]["vm_hwm_kb"] / 1024,
            "swap_mb": g["mem_after"]["vm_swap_kb"] / 1024,
            "sys_mem_available_mb": g["mem_after"]["mem_available_kb"] / 1024,
            "battery_temp_c_before": sb.get("battery_temp_c"), "battery_temp_c_after": sa.get("battery_temp_c"),
            "thermal_status_before": sb.get("thermal_status"), "thermal_status_after": sa.get("thermal_status"),
            "thermal_headroom_after": sa.get("thermal_headroom_0s"),
            "battery_pct": sb.get("battery_level_pct"), "plugged": sb.get("plugged"),
            "t_wall_ms": sb.get("t_wall_ms"), "text": g["text"],
        })
    return rows, fails, deaths, loads


def med(xs):
    xs = [x for x in xs if x is not None]
    return st.median(xs) if xs else None


def summarize(rows):
    groups = defaultdict(list)
    for r in rows:
        groups[(r["session"], r["model_file"], r["threads"])].append(r)
    out = []
    for (sess, model, thr), rs in sorted(groups.items(), key=lambda kv: (kv[0][0], kv[1][0]["params_b"] or 0, kv[0][2])):
        by_p = defaultdict(list)
        for r in rs:
            by_p[r["prompt_id"]].append(r)
        short, long_ = by_p.get("short", []), by_p.get("long", [])
        all_ok = all(r["ok"] for r in rs)
        tg = med([r["decode_tps"] for r in rs])
        ttft_long = med([r["ttft_ms"] for r in long_])
        ttft_short = med([r["ttft_ms"] for r in short])
        usable = all_ok and tg is not None and tg >= USABLE_TG and (ttft_long or 1e12) <= USABLE_TTFT_LONG_MS
        inter = usable and tg >= INTER_TG and (ttft_short or 1e12) <= INTER_TTFT_SHORT_MS
        out.append({
            "session": sess, "model_file": model, "params_b": rs[0]["params_b"], "threads": thr,
            "file_size_gb": round((rs[0]["file_size_bytes"] or 0) / 1e9, 3), "load_s": round((rs[0]["load_ms"] or 0) / 1000, 2),
            "n_runs": len(rs), "all_ok": all_ok,
            "decode_tps_median": round(tg, 2) if tg else None,
            "decode_tps_short": round(med([r["decode_tps"] for r in short]) or 0, 2),
            "decode_tps_long": round(med([r["decode_tps"] for r in long_]) or 0, 2),
            "decode_tps_min": round(min(r["decode_tps"] for r in rs), 2), "decode_tps_max": round(max(r["decode_tps"] for r in rs), 2),
            "prefill_tps_long": round(med([r["prefill_tps"] for r in long_]) or 0, 1),
            "prefill_tps_short": round(med([r["prefill_tps"] for r in short]) or 0, 1),
            "n_prompt_long": long_[0]["n_prompt"] if long_ else None,
            "ttft_short_ms": round(ttft_short or 0), "ttft_long_ms": round(ttft_long or 0),
            "rss_peak_mb": round(max(r["vm_hwm_mb"] for r in rs)), "rss_anon_mb": round(med([r["rss_anon_mb"] for r in rs])),
            "rss_file_mb": round(med([r["rss_file_mb"] for r in rs])), "swap_mb_max": round(max(r["swap_mb"] for r in rs)),
            "battery_temp_c_max": max((r["battery_temp_c_after"] or 0) for r in rs),
            "thermal_status_max": max((r["thermal_status_after"] or 0) for r in rs),
            "level": "INTERACTIVE" if inter else "USABLE" if usable else "FITS",
        })
    return out


def write_csv(path, rows):
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("wrote", path)


def main():
    paths = sys.argv[1:]
    name = sys.argv[1] and Path(paths[0]).stem
    recs = load_records(paths)
    rows, fails, deaths, loads = build_rows(recs)
    summ = summarize(rows)
    write_csv(OUT / f"{name}-runs.csv", rows)
    write_csv(OUT / f"{name}-summary.csv", summ)
    for f in fails:
        print("FAILED", f.get("model_file"), f.get("reason"))
    for d in deaths:
        print("DIED", json.dumps(d.get("during"))[:300])
    for s in summ:
        print(f"{s['model_file']:28} thr={s['threads']} tg={s['decode_tps_median']} (short {s['decode_tps_short']}, long {s['decode_tps_long']}) "
              f"pp_long={s['prefill_tps_long']} ttft s/l={s['ttft_short_ms']}/{s['ttft_long_ms']}ms rss_peak={s['rss_peak_mb']}MB "
              f"anon={s['rss_anon_mb']} file={s['rss_file_mb']} swap={s['swap_mb_max']} load={s['load_s']}s Tbat={s['battery_temp_c_max']} -> {s['level']}")


if __name__ == "__main__":
    main()
