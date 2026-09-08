# JBangLite

A stripped-down fork of [JBang](https://github.com/jbangdev/jbang) that keeps
only what is needed to build and run a single-file Java program together with
its `//DEPS`, `//JAVA` and `//SOURCES` directives. It was reduced for use by
[java-call-hierarchy-exporter](https://github.com/instreest/java-call-hierarchy-exporter),
but works for any script that only relies on those three directives.

```java
//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0
//JAVA 25
//SOURCES jche/**/*.java
```

## What is supported

| Feature | Notes |
| --- | --- |
| `jbang <script.java> [args]` / `jbang run` | compiles (if needed) and runs the script |
| `jbang build <script.java>` | compiles only |
| `jbang info classpath [--deps-only] <script.java>` | prints the class path (jar + dependencies) |
| `jbang info jar <script.java>` | prints the path of the built jar |
| `jbang jdk default [<version>]`, `jdk install <version>`, `jdk list` | used by the launcher scripts |
| `//DEPS g:a:v[:classifier][@type]` | resolved from Maven Central (mirrors/proxies from `~/.m2/settings.xml` are honoured); `@pom` entries act as BOMs |
| `//JAVA 17`, `//JAVA 17+`, `//JAVA 25.0.3` | the JDK is looked up (running JVM, `currentjdk`, `JAVA_HOME`, `PATH`, `~/.jbang/cache/jdks`) and downloaded when missing; a full version pins an exact JDK |
| `//SOURCES file-or-glob ...` | relative to the declaring file, recursive |
| `${property}` in directives | system properties, `-Dkey=value` and `os.detected.*` |
| Global options | `--verbose`, `--quiet`, `--fresh`, `--offline` |
| Script options | `--java <v>`, `--main <class>`, `--deps <gav,...>`, `-Dkey=value`, `-R<jvm option>` |

The cache layout is the same as full JBang (`~/.jbang/cache/jars/<file>.<hash>/<name>.jar`,
`~/.jbang/cache/jdks/<version>`, `~/.jbang/currentjdk`), and `run` still prints the
`java` command line and exits with status 255 so the launcher scripts
(`jbang`, `jbang.cmd`, `jbang.ps1`) can exec it.

Everything else from JBang was removed: catalogs/aliases/templates, `init`, `edit`,
`export`, `app`, `wrapper`, `trust`, remote/URL scripts, Kotlin/Groovy/JShell/Markdown
sources, native images, integrations, `//REPOS`, `//FILES`, `//JAVA_OPTIONS`,
`//JAVAC_OPTIONS`, `//MODULE`, `//MANIFEST`, and so on.

## Dependencies

The only third-party runtime dependencies are Maven Resolver (through
[MIMA](https://github.com/maveniverse/mima)) and the slf4j no-op binding it needs.
JDK download/unpacking, class-file inspection for the main class, jar creation,
OS detection and command-line quoting are implemented in the JDK's standard
library only (`src/main/java/dev/jbang`, 20 small classes).

## How JDKs are obtained

A JDK that is already on the machine is always preferred. Only when none of
them satisfies the requested version does JBangLite download one, and it does so
without depending on a JDK discovery service:

1. The list of downloadable JDKs is the JVM index that the
   [Coursier](https://github.com/coursier/jvm-index) project publishes to Maven
   Central as `io.get-coursier.jvm.indices:index-<platform>`. It is fetched with
   the same Maven Resolver used for `//DEPS`, so mirrors, proxies and
   credentials from `~/.m2/settings.xml` apply and the index is cached in the
   local repository. No other service has to be reachable.
2. The archive is downloaded from the distributor's own URL and its SHA-256 is
   verified against the checksum published next to it (`<url>.sha256.txt`, as
   Temurin does). A mismatch aborts the installation. A distribution that
   publishes no checksum only produces a warning.
3. Failed downloads are retried with exponential backoff, like the launcher
   scripts do.
4. The install is guarded by a lock file, so parallel JBangLite processes wait
   for each other instead of downloading the same JDK several times. The result
   is unpacked into a temporary directory and moved into place only after it has
   been validated.

The default distribution is Eclipse Temurin (GPLv2 with Classpath Exception).
Distributions with different licenses, such as the Oracle JDK, are never
downloaded unless they are asked for explicitly with `JBANG_JDK_DISTRO`.

Requesting a full version such as `//JAVA 25.0.3` installs and uses exactly that
version, which is what to use when the result must be reproducible. A request
like `25` or `25+` accepts any matching patch release.

## Environment variables

| Variable | Meaning |
| --- | --- |
| `JBANG_DIR` | base directory (default `~/.jbang`) |
| `JBANG_CACHE_DIR` | cache directory (default `$JBANG_DIR/cache`) |
| `JBANG_REPO` | local Maven repository to use instead of `~/.m2/repository` |
| `JBANG_DEFAULT_JAVA_VERSION` | JDK version to install when the script does not specify one (default 17) |
| `JBANG_JDK_DISTRO` | distributions to install from, most preferred first (default `temurin`) |
| `JBANG_JDK_INDEX` | path to a JDK index JSON file, or a Maven coordinate, replacing the default index |
| `JBANG_DOWNLOAD_RETRY` | extra download attempts (default 5, `0` disables retries) |
| `JBANG_DOWNLOAD_RETRY_DELAY` | seconds between attempts (default `0`, meaning exponential backoff) |

## Building

```bash
./gradlew assemble
```

produces `build/libs/jbang.jar` (self-contained), `build/distributions/jbang.tar`
and `jbang.zip` (root folder `jbang/` with `bin/jbang*`, as expected by the launcher
scripts) plus versioned `jbang-<version>.tar/.zip`. Pass `-PjbangVersion=x.y.z`
to set the version.

There is no test suite, release pipeline, or CI configuration in this fork.

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the JBangLite modifications are
provided under the same license. `jbang.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver (Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md)
for details and for the origin of code adapted from other projects.
