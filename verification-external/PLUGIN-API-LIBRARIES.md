# Separate API library verification

Verified on 2026-10-10 against baseline `c3505156a1667ddf8b3ab40aad0bbdf3ec49ed5b`.

## Regression and implementation

The real [BetterModel 3.5.0 Paper release](https://github.com/toxicity188/BetterModel/releases/tag/3.5.0)
exposes `org.semver4j.Semver` through `BetterModel.platform().semver()`. Its `paper-library`
resource declares a separate Maven library. The previous resolver reproduced:

```text
API signature exposes org.semver4j.Semver from an undeclared plugin or unsupported library loader; declare its provider explicitly
```

The resolver now retains each selected type's original `Class<?>` and actual source JAR.
The compiler receives only those definitions, and the module loader returns those original
classes. A library is accepted only with an identifiable local JAR and the same class
identity visible from the API provider. Server/engine duplicates, incompatible library
identities and undeclared plugin types still fail before module activation. Plugin main
loaders and source locations are captured on the server thread; JAR inspection remains
on the compiler worker. No Paper internal loader API is used.

Multi-release libraries use the current JVM's selected entries in the compiler view.
Multi-release plugin main JARs and manifest Class-Path remain unsupported.

## Automated checks

96 compiler/runtime tests passed on Temurin 21.0.12.1+1, including:

- Library types from a separate parent loader and from an additional JAR on the provider loader.
- Original type identity, generic bounds, native calls, shared static state and deferred initialization.
- Exclusion of unrelated library classes from compilation and module loading.
- Different library definitions, including different loaders for the same JAR, rejected by class identity.
- A genuinely shared library Class accepted across two declared API providers.
- Other plugin types requiring explicit dependencies, even when their source metadata is unavailable.
- Undeclared plugin descriptors, absent library sources, server class duplicates and manifest Class-Path rejected.
- Matching javac/runtime definitions for a multi-release library with a version-specific method.
- Bukkit/Paper provider snapshots, library identity and invalidation on provider disable.

This environment could not resolve the repository's Shadow Gradle plugin. The API,
compiler, runtime and workspace sources plus compiler/runtime test sources were compiled
with `javac -proc:none`; the compiled suites ran through a temporary offline Gradle 8.13
test harness with the existing Mockito agent. This is a targeted validation, not a full
repository build. Normal CI should run the repository build and all checks.

## Real BetterModel JAR check

`VerifyBetterModelApi.java` uses the unmodified BetterModel 3.5.0 Paper JAR, a separate
library `URLClassLoader`, Paper API 1.21.11 and Temurin 25.0.4.1+1. BetterModel 3.x requires
Java 25; the resolver change does not alter plugin Java requirements.

The check selected 261 API/signature types and verified:

- The user's original `modelcheck` source compiles and registers its command.
- The command invokes the real `BetterModel.model("demon_knight")`, returns `true` and sends
  the expected missing-model Component.
- BetterModel and Semver classes retain their original identities.
- A second generated module executes `BetterModel.platform().semver().getMajor()` directly.
- The same check against the previous resolver reproduces the exact Semver failure.

The platform and ModelManager are test proxies, with the manager returning `null`. This
does not start Paper or BetterModel's plugin lifecycle and does not verify rendering,
resource packs, installed models or live server command dispatch.

To reproduce, put the CodeEngine plugin JAR and the matching Paper API/dependency JARs
on `ENGINE_AND_PAPER_CP`. Do **not** put BetterModel or its private libraries on that
classpath. Prepare a separate directory with the JARs listed in its `paper-library`:

| Library | Version |
| --- | --- |
| `org.semver4j:semver4j` | 6.0.0 |
| `io.github.toxicity188:dynamicuv` | 1.2.2 |
| `io.github.toxicity188:java-mesh` | 0.0.1 |
| `com.github.ben-manes.caffeine:caffeine` | 3.2.4 |

Run from the repository root with a full JDK 25:

```sh
"$JAVA_HOME/bin/java" --class-path "$ENGINE_AND_PAPER_CP" \
  verification-external/VerifyBetterModelApi.java \
  /path/to/bettermodel-3.5.0-paper.jar /path/to/bettermodel-libraries /tmp/bettermodel-builds
```

The check performs no downloads. Run it as a separate JVM; it registers a test platform
in that isolated BetterModel classloader. Input hashes and results are retained in
[summary.json](results/plugin-api-libraries/summary.json),
[passing check](results/plugin-api-libraries/bettermodel-check.log) and
[before-fix failure](results/plugin-api-libraries/before-fix.log).
