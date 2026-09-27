# Third-party notices

The GNU General Public License, Version 3.0 (GPL-3.0-only), in `LICENSE.md` applies to Code Engine's original code and documentation. It does not replace the licenses of third-party components.

## Gradle Wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are Gradle build tooling distributed under the Apache License, Version 2.0. The scripts retain their original copyright and license headers. The wrapper JAR includes its license at `META-INF/LICENSE`; a copy is provided in `gradle/wrapper/LICENSE`.

## External dependencies

Paper API, JUnit, and optional Playwright/Chromium verification tools retain their respective upstream licenses. Their source code and dependency binaries are not vendored in this repository. Build dependencies are declared in the Gradle build files; browser verification setup is documented in `README.md`.
