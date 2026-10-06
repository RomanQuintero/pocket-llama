"""Figures for the report, drawn only from measured CSVs written by tools/analyze.py.

    python tools/figures.py            (needs matplotlib)
"""
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "results"
FIG = RES / "figures"
FIG.mkdir(parents=True, exist_ok=True)

LEVEL_COLOR = {"INTERACTIVE": "#2a9d8f", "USABLE": "#e9c46a", "FITS": "#e76f51"}


def read(name):
    p = RES / name
    if not p.exists():
        return []
    with open(p, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def f(x):
    return float(x) if x not in ("", None) else None


ladder = read("ladder-1-summary.csv")
cold_runs = read("cold-8b-1-runs.csv")
ladder_runs = read("ladder-1-runs.csv")

params = [f(r["params_b"]) for r in ladder]
labels = [r["model_file"].replace("-Q4_K_M.gguf", "") for r in ladder]


def ladder_plot(key, ylabel, fname, title, thresholds=(), logy=False, cold_key=None):
    fig, ax = plt.subplots(figsize=(6.4, 4.2))
    ys = [f(r[key]) for r in ladder]
    ax.plot(params, ys, "-", color="#999", zorder=1)
    for p, y, r, lab in zip(params, ys, ladder, labels):
        ax.scatter(p, y, s=60, color=LEVEL_COLOR.get(r["level"], "#555"), zorder=2)
        ax.annotate(f"{lab}\n{y:g}", (p, y), textcoords="offset points", xytext=(6, 6), fontsize=8)
    if cold_key and cold_runs:
        first3 = [f(r[cold_key]) for r in cold_runs if int(r["rep"]) < 3]
        first3.sort()
        med = first3[len(first3) // 2] if len(first3) % 2 else sum(first3[len(first3)//2-1:len(first3)//2+1]) / 2
        p8 = f(cold_runs[0]["params_b"])
        ax.scatter(p8, med, s=70, marker="D", facecolor="none", edgecolor="#264653", zorder=3, label="8B cold re-run (median of first 3 reps)")
        ax.annotate(f"cold {med:.1f}", (p8, med), textcoords="offset points", xytext=(-55, 6), fontsize=8, color="#264653")
        ax.legend(fontsize=8, loc="best")
    for t, lab in thresholds:
        ax.axhline(t, ls="--", lw=1, color="#888")
        ax.text(params[0], t, f" {lab}", va="bottom", fontsize=8, color="#555")
    if logy:
        ax.set_yscale("log")
        ax.yaxis.set_major_formatter(matplotlib.ticker.ScalarFormatter())
    ax.margins(x=0.12, y=0.18)
    ax.set_xscale("log")
    ax.set_xticks(params)
    ax.set_xticklabels([f"{p:.1f}B" for p in params])
    ax.set_xlabel("parameters (log scale)")
    ax.set_ylabel(ylabel)
    ax.set_title(title, fontsize=11)
    ax.grid(alpha=0.25)
    fig.tight_layout()
    fig.savefig(FIG / fname, dpi=150)
    plt.close(fig)
    print("wrote", FIG / fname)


ladder_plot("decode_tps_median", "decode tokens/s (median)", "1-decode-vs-size.png",
            "Decode speed vs model size (Galaxy Z Fold6, CPU, 6 threads)",
            thresholds=[(10, "INTERACTIVE ≥ 10 tok/s"), (5, "USABLE ≥ 5 tok/s")], logy=True, cold_key="decode_tps")
ladder_plot("prefill_tps_long", "prefill tokens/s (246-token prompt)", "2-prefill-vs-size.png",
            "Prompt processing speed vs model size", logy=True, cold_key="prefill_tps")
ladder_plot("rss_peak_mb", "peak process RSS (MB)", "3-ram-vs-size.png", "Peak memory vs model size")
ladder_plot("load_s", "model load time (s)", "4-load-vs-size.png", "Model load time vs model size")

# 5. thermal series: every measured run in execution order, decode tok/s + battery temp + max big-core state
if ladder_runs or cold_runs:
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), sharey=False)
    for ax, runs, title in ((axes[0], ladder_runs, "Ladder (fixed order 0.6B → 8B)"),
                            (axes[1], cold_runs, "8B alone, from cold")):
        if not runs:
            ax.set_visible(False)
            continue
        runs = sorted(runs, key=lambda r: int(r["t_wall_ms"]))
        t0 = int(runs[0]["t_wall_ms"])
        xs = [(int(r["t_wall_ms"]) - t0) / 60000 for r in runs]
        ys = [f(r["decode_tps"]) for r in runs]
        cols = [{"0": "#2a9d8f", "1": "#8ab17d", "2": "#e9c46a", "3": "#f4a261"}.get(r["thermal_status_after"], "#e76f51") for r in runs]
        ax.scatter(xs, ys, c=cols, s=40, zorder=2)
        ax.plot(xs, ys, color="#bbb", zorder=1)
        ax.set_yscale("log")
        ax.set_xlabel("minutes since first measured run")
        ax.set_ylabel("decode tokens/s")
        ax2 = ax.twinx()
        ax2.plot(xs, [f(r["battery_temp_c_after"]) for r in runs], color="#c1121f", ls=":", lw=1.5)
        ax2.set_ylabel("battery °C (proxy)", color="#c1121f")
        ax.set_title(title + "\n(dot colour = Android thermal status after run: 0 green … 3 orange)", fontsize=9)
        ax.grid(alpha=0.25)
    fig.tight_layout()
    fig.savefig(FIG / "5-thermal.png", dpi=150)
    plt.close(fig)
    print("wrote", FIG / "5-thermal.png")
