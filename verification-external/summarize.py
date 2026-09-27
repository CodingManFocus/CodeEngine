#!/usr/bin/env python3
"""Summarize paired samples; report per-fork medians instead of treating all samples as independent forks."""
import argparse
import csv
import json
from pathlib import Path
import statistics

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("results", type=Path)
args = parser.parse_args()
summary = {}
for directory in sorted(args.results.glob("fork-*")):
    samples = list(csv.DictReader((directory / "samples.csv").open()))
    fork = {}
    for workload in sorted({sample["workload"] for sample in samples}):
        groups = {path: {int(sample["sample"]): sample for sample in samples if sample["path"] == path and sample["workload"] == workload} for path in ("native", "module")}
        if groups["native"].keys() != groups["module"].keys(): raise SystemExit("Unpaired samples")
        details = {}
        for path, group in groups.items():
            nanos = [float(row["nanos"]) / int(row["operations"]) for row in group.values()]
            allocated = [float(row["bytes"]) / int(row["operations"]) for row in group.values()]
            details[path] = {"medianNs": statistics.median(nanos), "p95Ns": sorted(nanos)[int(len(nanos) * .95)], "medianBytes": statistics.median(allocated)}
        differences = [float(groups["module"][index]["nanos"]) / int(groups["module"][index]["operations"]) - float(groups["native"][index]["nanos"]) / int(groups["native"][index]["operations"]) for index in groups["native"]]
        details["pairedMedianDeltaNs"] = statistics.median(differences)
        details["moduleVsNativeMedianPercent"] = 100 * (details["module"]["medianNs"] / details["native"]["medianNs"] - 1)
        details["pairs"] = len(differences)
        fork[workload] = details
    cold = list(csv.DictReader((directory / "cold-loads.csv").open()))
    fork["coldLoadMillis"] = [int(row["nanos"]) / 1e6 for row in cold]
    summary[directory.name] = fork
(args.results / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
