#!/usr/bin/env python3
"""Run the public PlaceholderAPI integration on disposable, loopback-only Paper servers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("paper", "placeholder-api", "engine-jar", "verification-jar", "java", "prepared-server", "work", "results"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--scenario", choices=("benchmark", "reentrant", "engine-stop", "engine-disable-hook", "provider-command-stop", "normal-stop"), default="benchmark")
    parser.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("The disposable Minecraft server requires --accept-eula.")
    args.results.mkdir(parents=True, exist_ok=True)
    args.work.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent.parent
    sources = []
    for project in ("codeengine-api", "codeengine-compiler", "codeengine-plugin", "verification-external"):
        sources.extend(path for path in (root / project / "src/main").rglob("*") if path.is_file())
    manifest = "".join(str(path.relative_to(root)) + " " + digest(path) + "\n" for path in sorted(sources))
    environment = {
        "sourceManifestSha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "gitBaseCommit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True).stdout.strip(),
        "platform": platform.platform(), "cpuCount": os.cpu_count(),
        "java": subprocess.run([str(args.java), "-version"], capture_output=True, text=True, check=True).stderr,
        "paperSha256": digest(args.paper), "placeholderApiSha256": digest(args.placeholder_api),
        "engineSha256": digest(args.engine_jar), "verificationSha256": digest(args.verification_jar),
        "forks": args.forks, "scenario": args.scenario, "warmupTicks": 160, "measurementTicks": 240,
        "paperPluginRemapping": "default enabled",
        "paperVersion": "1.21.11 build 132", "placeholderApiVersion": "2.11.6",
    }
    cpuInfo = Path("/proc/cpuinfo")
    if cpuInfo.exists():
        environment["cpuModel"] = next((line.split(":", 1)[1].strip() for line in cpuInfo.read_text().splitlines() if line.startswith("model name")), "unknown")
    for key in ("cpu.max", "memory.max", "cpu.stat"):
        path = Path("/sys/fs/cgroup") / key
        if path.exists(): environment[key] = path.read_text()
    (args.results / "source-manifest.txt").write_text(manifest)
    (args.results / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    for fork in range(1, args.forks + 1):
        run_fork(args, fork)
    cpuStats = Path("/sys/fs/cgroup/cpu.stat")
    if cpuStats.exists(): environment["cpu.stat.after"] = cpuStats.read_text()
    (args.results / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")


def run_fork(args, fork):
    server = args.work / f"fork-{fork}"
    if server.exists(): raise SystemExit(f"Refusing to reuse existing server: {server}")
    server.mkdir()
    shutil.copy2(args.paper, server / "paper.jar")
    for name in ("libraries", "versions", "cache"):
        shutil.copytree(args.prepared_server / name, server / name)
    plugins = server / "plugins"
    data = plugins / "CodeEngine"
    data.mkdir(parents=True)
    shutil.copy2(args.engine_jar, plugins / "CodeEngine.jar")
    shutil.copy2(args.placeholder_api, plugins / "PlaceholderAPI.jar")
    shutil.copy2(args.verification_jar, plugins / "CodeEngineExternalVerification.jar")
    (data / "config.yml").write_text("autoLoad: false\nseedExample: false\nwebPort: 17987\nunloadTimeoutSeconds: 2\n")
    (plugins / "PlaceholderAPI").mkdir()
    (plugins / "PlaceholderAPI/config.yml").write_text("check_updates: false\ncloud_enabled: false\n")
    (plugins / "bStats").mkdir()
    (plugins / "bStats/config.yml").write_text("enabled: false\n")
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(
        'server-ip=127.0.0.1\nserver-port=25587\nonline-mode=false\nlevel-seed=123456789\n'
        'level-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
        'generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
    )
    (server / "config").mkdir()
    (server / "config/paper-global.yml").write_text("spark:\n  enabled: false\n")
    logfile = server / "console.log"
    output = logfile.open("w")
    command = [str(args.java), "-Xms512M", "-Xmx2G", "-Dterminal.jline=false", "-Dterminal.ansi=false",
               "-jar", "paper.jar", "nogui"]
    process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
    def console(value):
        process.stdin.write(value + "\n")
        process.stdin.flush()
    def wait_for(needle, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            log = logfile.read_text(errors="replace")
            if process.poll() is not None: raise RuntimeError(f"Server exited {process.returncode}: {log[-6000:]}")
            if "EXTERNAL VERIFICATION FAILED" in log: raise RuntimeError(log[-6000:])
            if needle in log: return
            time.sleep(0.25)
        raise TimeoutError(logfile.read_text(errors="replace")[-6000:])
    print(f"Fork {fork}: starting Paper with real PlaceholderAPI", flush=True)
    try:
        wait_for("Done (", 180)
        console("ceexternalverify" + (" " + args.scenario if args.scenario != "benchmark" else ""))
        if args.scenario == "benchmark":
            wait_for("EXTERNAL LIFECYCLE PASS", 180)
            print(f"Fork {fork}: lifecycle passed, measuring paired API and command workloads", flush=True)
        if args.scenario == "normal-stop":
            wait_for("EXTERNAL NORMAL STOP READY", 120)
        else:
            wait_for("EXTERNAL VERIFICATION PASS", 120)
            print(f"Fork {fork}: passed", flush=True)
    finally:
        if process.poll() is None:
            console("stop")
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
        output.close()
        destination = args.results / f"fork-{fork}"
        destination.mkdir(exist_ok=True)
        shutil.copy2(logfile, destination / "server.log")
        test_data = plugins / "CodeEngineExternalVerification"
        for name in ("checks.txt", "samples.csv", "cold-loads.csv", "failure.txt", "generated-source.java", "module.jar", "native-shutdown.txt"):
            if (test_data / name).exists(): shutil.copy2(test_data / name, destination / name)
        if (data / "builds").exists():
            for generated in (data / "builds").rglob("Entry.java"):
                target = destination / "generated" / generated.relative_to(data / "builds")
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(generated, target)
        if process.returncode != 0: raise RuntimeError(f"Server exit: {process.returncode}")
        log = logfile.read_text(errors="replace")
        if args.scenario == "normal-stop":
            moduleData = data / "data/normalstop"
            checks = {
                "module enabled before server stop": (moduleData / "enable.marker").exists(),
                "native plugin disabled while provider enabled": "providerEnabled=true" in (destination / "native-shutdown.txt").read_text(),
                "module remained loaded until provider stop": "moduleStillLoaded=true" in (destination / "native-shutdown.txt").read_text(),
                "module disable skipped after provider invalidation": not (moduleData / "disable.marker").exists(),
                "module external cleanup skipped after provider invalidation": not (moduleData / "cleanup.marker").exists(),
                "stopped module artifact retired": not any((data / "builds").glob("normalstop-*")),
                "skipped cleanup explicitly logged": "Dependency stopped; user disable/cleanup skipped: normalstop" in log,
            }
            with (destination / "checks.txt").open("a") as report:
                for name, passed in checks.items(): report.write(("PASS " if passed else "FAIL ") + name + "\n")
            if not all(checks.values()): raise RuntimeError(f"Ordinary shutdown contract changed: {checks}")
            print(f"Fork {fork}: documented ordinary-shutdown behavior passed", flush=True)
        if "Error occurred while" in log or "Could not pass event" in log:
            raise RuntimeError(f"Plugin lifecycle error; inspect {destination / 'server.log'}")


if __name__ == "__main__":
    main()
