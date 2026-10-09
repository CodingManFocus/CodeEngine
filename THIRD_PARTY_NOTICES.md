# Third-party notices

The GNU General Public License, Version 3.0 (GPL-3.0-only), in `LICENSE.md` applies to Code Engine's original code and documentation. It does not replace the licenses of third-party components.

## Gradle Wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are Gradle build tooling distributed under the Apache License, Version 2.0. The scripts retain their original copyright and license headers. The wrapper JAR includes its license at `META-INF/LICENSE`; a copy is provided in `gradle/wrapper/LICENSE`.

## External dependencies

Paper API, JUnit, and optional Playwright/Chromium verification tools retain their respective upstream licenses. Their source code and dependency binaries are not vendored in this repository. Build dependencies are declared in the Gradle build files; browser verification setup is documented in `README.md`.

## Studio runtime dependencies

The plugin bundles React, React DOM, Scheduler, CodeMirror 6, Lezer, and their runtime dependencies under their respective MIT licenses. Exact versions are locked in `codeengine-webide/package-lock.json`. The Studio build collects each bundled package's full license into `webide/THIRD_PARTY_LICENSES.txt` in the JAR (also available at `/THIRD_PARTY_LICENSES.txt` from Studio). TypeScript, esbuild, tsx and Playwright are build/test tools and are not bundled into the plugin.

## WebIDE artifact resolver

The plugin embeds Apache Maven Resolver 1.9.22, Maven's resolver provider 3.9.9, and their runtime dependencies. Maven, Maven Resolver, Apache HttpComponents, Apache Commons, and `javax.inject` use Apache License 2.0; SLF4J uses the MIT license; Plexus components retain their upstream Apache/MIT licenses. Packages are relocated under `kr.codenamemc.codeengine.internal` to avoid conflicts with server plugins. Upstream `META-INF/LICENSE` and `META-INF/NOTICE` files are preserved and appended by the shaded build. Dependency versions and the complete dependency graph are available through the Gradle `dependencies` task.

Paper API and its transitive API dependencies are downloaded from the official Paper/Maven repositories at runtime, only when an authenticated WebIDE requests intelligence artifacts. Java SE class files are copied from the server's installed JDK for that WebIDE session. These artifacts retain their respective upstream licenses and are cached separately from the distributed Code Engine plugin.
