# Java metadata fixtures

These are small test classes compiled with JDK 21 (`javac --release 21
-parameters -g`). The `org.bukkit` classes deliberately model a tiny API for
browser tests; they are not a redistributed Paper implementation.

- `classfiles.json`: base64 bytes of the `java/fixture` classes. Exercises real
  constant pools, signatures, modified UTF-8, record bootstrap metadata,
  visibility, inheritance and parameter names without requiring Java for npm
  tests.
- `browser-api.jar.base64`: a DEFLATE-compressed JAR of the `java/org/bukkit`
  classes. Browser integration tests serve these genuine classfiles to the Web
  Worker.

To regenerate, compile all sources into a temporary directory, base64-encode
each `fixture/*.class` into `classfiles.json` keyed by its filename, and ZIP the
`org/bukkit/**/*.class` files using their paths relative to that temporary
directory. Base64-encode the resulting ZIP into `browser-api.jar.base64`.
