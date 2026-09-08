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
| `//JAVA 17` / `//JAVA 17+` | the JDK is looked up (running JVM, `currentjdk`, `JAVA_HOME`, `PATH`, `~/.jbang/cache/jdks`) and downloaded when missing (Adoptium/Temurin API, falling back to the Oracle JDK download site) |
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

## Environment variables

| Variable | Meaning |
| --- | --- |
| `JBANG_DIR` | base directory (default `~/.jbang`) |
| `JBANG_CACHE_DIR` | cache directory (default `$JBANG_DIR/cache`) |
| `JBANG_REPO` | local Maven repository to use instead of `~/.m2/repository` |
| `JBANG_DEFAULT_JAVA_VERSION` | JDK version to install when the script does not specify one (default 17) |
| `JBANG_JDK_DOWNLOAD_URL` | URL template that replaces the built-in download sources; placeholders `{version}`, `{os}`, `{oracleos}`, `{arch}`, `{ext}` |

## Building

```bash
./gradlew assemble
```

produces `build/libs/jbang.jar` (self-contained), `build/distributions/jbang.tar`
and `jbang.zip` (root folder `jbang/` with `bin/jbang*`, as expected by the launcher
scripts) plus versioned `jbang-<version>.tar/.zip`. Pass `-PjbangVersion=x.y.z`
to set the version.

### Bundles with a built-in JDK 25

```bash
./gradlew bundleDist                        # Temurin 25 for this machine's platform
./gradlew bundleDist -PjlinkJdk=/path/to/jdk-25   # use a local JDK 25 (needs its jmods/)
./gradlew bundleDist -PjlinkPlatform=windows-x64 -PjlinkTool=/path/to/jdk-25   # cross-build
```

produces `build/distributions/jbang-<version>-<os>-<arch>.tar.gz` / `.zip` that
additionally contain `jbang/runtime/`, a JDK 25 shrunk with `jlink` to the modules
`jbang.jar` itself needs (`java.base`, `java.compiler`, `java.naming`,
`java.security.jgss`, `java.sql`, `jdk.charsets`, `jdk.unsupported`). The launcher
scripts run `jbang.jar` on that runtime, so JBangLite starts without any JDK on the
machine. The runtime is **not** used for scripts: it has no `javac`, and the JDK that
compiles and runs a script (`//JAVA`, `currentjdk`) is located or downloaded by JBang
as usual, exactly as in a distribution without a bundled runtime. The JDK to shrink is
downloaded from the Adoptium API by default; `-PjlinkJdkUrl=<url>` points at a
mirror, and `-PjlinkJdkUrl`/`-PjlinkPlatform` select other platforms. jlink requires
a JDK of the same major version as the jmods, so cross-building needs
`-PjlinkTool=<JDK 25 home>` when Gradle itself runs on an older JDK.

There is no test suite, release pipeline, or CI configuration in this fork.

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the JBangLite modifications are
provided under the same license. `jbang.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver (Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md)
for details and for the origin of code adapted from other projects.
