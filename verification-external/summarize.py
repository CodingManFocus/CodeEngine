#!/usr/bin/env python3
"""Validate complete paired evidence before atomically replacing its summary."""
import argparse
import csv
import json
from pathlib import Path
import statistics

workloads = {"apiSingle": 1024, "apiMultiple": 1024, "commandSingle": 128}


def readRows(path):
    with path.open() as source:
        return list(csv.DictReader(source))


def summarize(results):
    environment = json.loads((results / "environment.json").read_text())
    if environment["scenario"] != "benchmark":
        raise ValueError("Only complete benchmark runs can be summarized")
    count = environment["forks"]
    if not isinstance(count, int) or count < 1:
        raise ValueError("No benchmark forks")
    directories = sorted(results.glob("fork-*"))
    if {item.name for item in directories} != {f"fork-{i}" for i in range(1, count + 1)}:
        raise ValueError("Missing or unexpected forks")
    measurementTicks = environment["measurementTicks"]
    if not isinstance(measurementTicks, int) or measurementTicks < 2:
        raise ValueError("Invalid measurement count")
    summary = {}
    for directory in directories:
        if (directory / "failure.txt").exists():
            raise ValueError(f"Failed verification: {directory}")
        if "EXTERNAL VERIFICATION PASS" not in (directory / "server.log").read_text():
            raise ValueError(f"Missing completion marker: {directory}")
        checks = (directory / "checks.txt").read_text().splitlines()
        if not checks or any(not line.startswith("PASS ") for line in checks):
            raise ValueError(f"Invalid correctness evidence: {directory}")
        groups = {(workload, path): {} for workload in workloads for path in ("native", "module")}
        for row in readRows(directory / "samples.csv"):
            key = (row["workload"], row["path"])
            if key not in groups:
                raise ValueError("Unexpected workload/path")
            index = int(row["sample"])
            if index in groups[key]:
                raise ValueError("Duplicate sample")
            operations, nanos, allocated = int(row["operations"]), int(row["nanos"]), int(row["bytes"])
            if operations != workloads[key[0]] or nanos <= 0 or allocated < 0:
                raise ValueError("Invalid measurement")
            groups[key][index] = (nanos / operations, allocated / operations)
        expected = set(range(measurementTicks))
        if any(set(group) != expected for group in groups.values()):
            raise ValueError("Missing/unpaired samples")
        fork = {"correctnessChecks": len(checks)}
        for workload in workloads:
            details = {}
            for path in ("native", "module"):
                rows = groups[workload, path]
                nanos = [rows[index][0] for index in sorted(expected)]
                allocated = [rows[index][1] for index in sorted(expected)]
                details[path] = {"medianNs": statistics.median(nanos),
                                 "p95Ns": sorted(nanos)[int(len(nanos) * .95)],
                                 "medianBytes": statistics.median(allocated)}
            differences = [groups[workload, "module"][i][0] - groups[workload, "native"][i][0] for i in sorted(expected)]
            allocationDifferences = [groups[workload, "module"][i][1] - groups[workload, "native"][i][1] for i in sorted(expected)]
            details["pairedMedianDeltaNs"] = statistics.median(differences)
            details["pairedMedianDeltaBytes"] = statistics.median(allocationDifferences)
            details["pairs"] = len(differences)
            fork[workload] = details
        cold = readRows(directory / "cold-loads.csv")
        if len(cold) != 6 or {int(row["index"]) for row in cold} != set(range(6)) or any(int(row["nanos"]) <= 0 for row in cold):
            raise ValueError("Incomplete cold-load measurements")
        fork["coldLoadMillis"] = [int(row["nanos"]) / 1e6 for row in cold]
        summary[directory.name] = fork
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    summary = summarize(args.results)
    serialized = json.dumps(summary, indent=2) + "\n"
    temporary = args.results / "summary.json.tmp"
    temporary.write_text(serialized)
    temporary.replace(args.results / "summary.json")
    print(serialized, end="")


if __name__ == "__main__":
    main()
