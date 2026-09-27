# External plugin API verification

This test-only plugin runs on a fresh local Paper server with the real, free
[PlaceholderAPI 2.11.6](https://github.com/PlaceholderAPI/PlaceholderAPI/releases/tag/2.11.6).
It is **not** part of the CodeEngine distribution. Do not install it on a live
server: its final checks deliberately disable PlaceholderAPI or CodeEngine.

The completed run summary and its evidence-availability limitations are documented
in [REPORT.md](REPORT.md).

## What is compared

`NativeApiWork.java` and `external.ce` independently call the same public
`PlaceholderAPI.setPlaceholders(OfflinePlayer, String)` method with the same
precomputed input strings and the same registered expansion instance. Both
consume the resulting string hash. There is no surrogate implementation of
PlaceholderAPI in the timed path.

| Workload | One measured operation |
| --- | --- |
| `apiSingle` | Resolve one placeholder and consume the resulting hash |
| `apiMultiple` | Resolve four placeholders and consume the resulting hash |
| `commandSingle` | Paper command dispatch, argument parsing, one placeholder resolution, observable result |

The first two workloads enter native and generated code through matching
`IntUnaryOperator` call sites. They isolate API-call behavior from engine command
lifecycle tracking. The command workload includes that tracking and real Paper
command dispatch. Its native and module command names have equal lengths.

Every JVM fork uses 160 warmup ticks and 240 measured ticks. Within each tick,
the native/module order is randomized deterministically for each pair. Each API
batch has 1,024 operations; each command batch has 128. All timed work executes
on the server thread. The accumulated result is written to a volatile sink after
each API batch; each command exposes its result to the harness. Allocation is
measured with the current thread's `ThreadMXBean` counter. Files and reporting
are outside the timed region. `summarize.py` reports per-fork medians, p95 batch
means, and paired median deltas; a p95 is **not** an individual-call latency or
server tick percentile.

Six complete module compiles/loads are timed separately. Their wall time includes
compilation, dependency indexing, scheduled handoff, and lifecycle setup; it is
not a comparison with a Java plugin's prebuilt startup cost. Repeated loads have
warm JVM/compiler/OS caches even though their module class loaders are fresh.

## Correctness checks

- Missing dependencies and API imports without a declaration fail before activation.
- The module and native plugin observe the exact same `PlaceholderAPI.class`.
- A module-defined `PlaceholderExpansion` is invoked by the actual provider.
- A failed enable hook releases resources registered with `ctx.onClose`.
- Six load/unload cycles remove the module expansion and command, release the
  callback held by the harness, and preserve the native expansion.
- Native/module results match for all 256 inputs in all three workloads.
- Provider shutdown removes dependent entry points without invoking user cleanup
  against the stopped provider. Re-enabling the same provider object does not
  make it usable again; a server restart is required.
- Separate fresh-server scenarios stop the provider inside enable or a command,
  or stop CodeEngine inside enable or disable. The executing callback can still
  load an anonymous inner class, then its generated artifact is removed after
  return. No user cleanup runs after engine/provider shutdown.

The `normal-stop` probe also records the important native-plugin difference:
`requires` does not add a Paper dependency edge. A provider may stop before
CodeEngine even during an ordinary server stop, so module `disable`/`onClose`
hooks can be skipped. Explicitly unload the module while providers remain active
when that cleanup must run; do not rely on these hooks as the only durable save.

This validates the supported Bukkit `plugin.yml` provider path. It does not prove
compatibility with every plugin API, a Paper `paper-plugin.yml` provider, nested
library loaders, concurrent third-party callbacks, live provider replacement,
or plugin-identity-sensitive APIs. It is not a multiplayer TPS/load test.

## Reproduce

Use a full JDK 21 and the following pinned artifacts. The final runs use Paper's
normal plugin remapping (no remapping-disable override):

| Input | Download | SHA-256 |
| --- | --- | --- |
| Paper 1.21.11 build 132 | [Official download](https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar) | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |
| PlaceholderAPI 2.11.6 | [Official Maven artifact](https://repo.extendedclip.com/releases/me/clip/placeholderapi/2.11.6/placeholderapi-2.11.6.jar) | `b20cb09db1cd79f76b9172e5114dfde6f34c558069e3bd7b72b14f718bc5dffd` |

```sh
./gradlew :codeengine-plugin:jar :verification-external:jar \
  -PplaceholderApiJar=/absolute/path/placeholderapi-2.11.6.jar

# In a disposable directory containing paper.jar; this prepares libraries,
# versions, and cache. Paper downloads the corresponding Mojang server itself.
java -Dpaperclip.patchonly=true -jar paper.jar

python3 verification-external/run-integration.py \
  --paper /absolute/path/paper.jar \
  --placeholder-api /absolute/path/placeholderapi-2.11.6.jar \
  --engine-jar "$PWD/codeengine-plugin/build/libs/codeengine-plugin-0.1.0.jar" \
  --verification-jar "$PWD/verification-external/build/libs/verification-external-0.1.0.jar" \
  --java /absolute/path/jdk21/bin/java \
  --prepared-server /absolute/path/prepared-paper \
  --work /tmp/codeengine-external-new-disposable-servers \
  --results "$PWD/verification-external/results/final" \
  --forks 3 --accept-eula

python3 verification-external/summarize.py verification-external/results/final
```

Keep disposable server working directories outside synchronized workspaces; an
external synchronizer can restore deleted build files and distort lifecycle checks.

Use a fresh `--work` and `--results` directory with `--forks 1 --scenario NAME`
for each destructive scenario: `reentrant`, `engine-stop`, `engine-disable-hook`,
`provider-command-stop`, and `normal-stop`. Do not run other CPU-heavy jobs during timing.
The runner binds to loopback, uses a flat disposable world and offline mode,
disables bStats/PAPI network checks/spark, and refuses to reuse an existing server
directory. `--accept-eula` confirms the Minecraft EULA for these disposable servers.
