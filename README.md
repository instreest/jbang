# JBangLite

A reduced fork of [JBang](https://github.com/jbangdev/jbang) for running
single-file Java programs. The `//`-directives behave exactly as in JBang,
because the code that parses them is mirrored from JBang unchanged; what was
removed are the subcommands, the other source languages and the release and
installer machinery. It was reduced for use by
[java-call-hierarchy-exporter](https://github.com/instreest/java-call-hierarchy-exporter).

```java
//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0
//JAVA 25
//SOURCES jche/**/*.java
```

## Installing into a project

JBangLite is not released as a package: a project installs the wrapper into its
own repository and commits it, so anyone who checks the project out can run its
scripts without installing JBangLite — or a JDK — first.

```bash
curl -Ls https://raw.githubusercontent.com/instreest/jbang/main/dist/install.sh | bash
git add jbangw && git commit -m "Add the JBangLite wrapper"
jbangw/jbang src/Hello.java
```

`install.cmd` does the same on Windows. The installer writes the launchers,
itself, `LICENSE`, a `.gitignore` and `jbanglite.properties` — the repository,
revision and jar checksum the launchers use — into `jbangw/`. On the first run
the launcher downloads `dist/jbang.jar` from that revision, checks it against
the recorded SHA-256 and caches it in `jbangw/.jbang/`, which the `.gitignore`
keeps out of the project.

Re-running the installer updates an installation in place: it refreshes the
launchers, itself and the pinned revision, and drops the cached jar so the next
run fetches the matching one. `JBANGLITE_REF=<tag|commit>` pins another
revision, `JBANGLITE_REPO` another fork.

Everything a project installs lives in [`dist/`](dist) — the launchers, the
installers, `LICENSE`, `gitignore`, `README.md` and the built `jbang.jar` with
its checksum. Run `misc/update-dist.sh` to refresh it (it rebuilds the jar and
copies the launchers and `LICENSE` there) and commit the result whenever a
change should reach the projects that installed the wrapper;
`misc/update-dist.sh --check` reports whether it is up to date.

## Directives

All directives JBang understands are parsed by the mirrored parser and are
applied the same way:

| Directive | Behaviour |
| --- | --- |
| `//DEPS <gav>` | resolved from Maven Central; `@pom` entries act as BOMs, URLs become JitPack coordinates, `@Grab` annotations are read too |
| `//DEPS <file.java>` | built as its own project and put on the class path |
| `//SOURCES <file-or-glob>` | compiled together with the script, recursively |
| `//FILES [<target>=]<file-or-glob>` | copied into the jar, optionally under another name or folder |
| `//JAVA <version>[+]` | selects the JDK, downloading one when needed |
| `//REPOS [<id>=]<url-or-alias>` | extra Maven repositories, `@GrabResolver` included |
| `//MAIN <class>` | the class to run |
| `//MODULE [<name>]` | builds and runs as a module, generating `module-info.java` when there is none |
| `//MANIFEST <key>=<value>` | added to the jar manifest; `Add-Opens`, `Add-Exports` and `Enable-Native-Access` also reach the `java` command line |
| `//JAVAAGENT` | records the `premain` and `agentmain` classes in the manifest |
| `//COMPILE_OPTIONS`, `//JAVAC_OPTIONS` | passed to `javac` |
| `//RUNTIME_OPTIONS`, `//JAVA_OPTIONS` | passed to `java` |
| `//PREVIEW` | compiles and runs with `--enable-preview` |
| `//CDS` | class data sharing, archive next to the jar |
| `//GAV`, `//DESCRIPTION` | written into the `pom.xml` inside the jar |
| `//DOCS` | parsed and kept, but JBangLite has no `info docs` command to show it |
| `//NOINTEGRATIONS`, `//NATIVE_OPTIONS` | inert: JBangLite has no build-time integrations and no native image |
| `//GROOVY`, `//KOTLIN` | inert: only `.java` sources are accepted |
| `${property}` in any directive | system properties, `-Dkey=value` and `os.detected.*` |

## What was removed

| Kept | Removed |
| --- | --- |
| `run` (the default), `info classpath`, `version` | `build`, `info jar`, `jdk default/install/list`, `edit`, `init`, `alias`, `template`, `catalog`, `trust`, `cache`, `completion`, `wrapper`, `app`, `export`, `config`, `deps`, `info tools/docs` |
| `.java` sources | `.jsh`, `.kt`, `.groovy`, `.md`, jars and GAVs as scripts |
| local files | remote scripts, gists, catalogs and aliases |
| plain jars | native images, integrations (Quarkus and friends) |
| a `tar`/`zip` distribution | releases, installers, packages, the update mechanism, CI |

Global options are `--verbose`, `--quiet`, `--fresh` and `--offline`. Script
options are `--java`, `--main`, `--module`, `--deps`, `--repos`,
`-C<compiler option>`, `-R<jvm option>`, `-Dkey=value`, `--enable-preview`,
`-ea`, `-esa` and `--cds`.

The cache layout is the same as full JBang (`~/.jbang/cache/jars/<file>.<hash>/<name>.jar`,
`~/.jbang/cache/jdks/<version>`, `~/.jbang/currentjdk`), and `run` still prints the
`java` command line and exits with status 255 so the launcher scripts
(`jbang`, `jbang.cmd`) can exec it.

### What the launcher scripts need

The scripts (`jbang` for POSIX shells, `jbang.cmd` for Windows) find or install a JDK on their
own and then run `jbang.jar` with it; they never call a subcommand of the jar.
Which JVM runs `jbang.jar` hardly matters, so the search is deliberately short:

1. `$JBANG_DIR/currentjdk` (the JDK the jar installed for a script)
2. `$JBANG_CACHE_DIR/jdks/bootstrap`
3. `JAVA_HOME`
4. `java` on the `PATH`

Anything Java 11 or newer is accepted. If nothing is found, the scripts download
the newest Temurin 25 into `$JBANG_CACHE_DIR/jdks/bootstrap`, verify its
published SHA-256 and use that. `jbang.cmd` is self-contained — it uses the
`curl`, `tar` and `certutil` that Windows ships with, so no PowerShell is
involved and there is no `jbang.ps1` in this fork.

The download uses the very same JVM index the jar uses for `//JAVA`, the
Coursier index published on Maven Central
(`io.get-coursier.jvm.indices:index-<platform>`), so no JDK discovery service is
involved, and `JBANG_JVM_INDEX_BASEURL` points both of them at a corporate mirror.

The JDK a script asks for with `//JAVA` is still provisioned by the jar; the
bootstrap JDK only exists to get `jbang.jar` started. `jdk default`, `jdk
install` and `jdk list` were therefore removed.

## Staying in sync with JBang

The tree is split in three, so that fixes JBang makes to the directive handling
can be taken over without merging:

| | Contents | Maintenance |
| --- | --- | --- |
| Mirror | the files in `misc/upstream-mirror.txt`, among them `Directives.java` and its test | copied from JBang unchanged, never edited here |
| Shims | the files in `misc/upstream-shims.txt` (`Util`, `JavaUtil`, `DependencyUtil`) | upstream's API with a reduced implementation, checked by hand when upstream changes them |
| JBangLite | everything else | this fork's own code |

```bash
misc/sync-upstream.sh            # take the mirrored files from upstream/main
misc/sync-upstream.sh <ref>      # ... or from a specific tag or commit
./gradlew build
```

The script reports which commits touched the mirrored files and which touched
the shims, and records the synced revision in `misc/upstream-ref.txt`. Because
it only ever copies whole files, an upstream commit that also changes hundreds
of unrelated files costs nothing here, which is why this is a sync and not a
`git cherry-pick`.

## Dependencies

The only third-party runtime dependencies are Maven Resolver (through
[MIMA](https://github.com/maveniverse/mima)), the slf4j no-op binding it needs
and the jspecify annotations used by the mirrored files.
JDK download/unpacking, class-file inspection for the main class, jar creation,
OS detection and module-info generation are implemented with the JDK's standard
library only. `jbang.jar` itself needs Java 11 or later to run (JBang targets
Java 8); the JDK used for scripts is whatever `//JAVA` asks for.

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
| `JBANG_JVM_INDEX_BASEURL` | Maven repository to read the JVM index from (default `https://repo1.maven.org/maven2`) |
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

`./gradlew test` runs the test suite: the mirrored `TestDirectives` from JBang
and functional tests for the launcher scripts and the wrapper installer. There
is no release pipeline or CI configuration in this fork; `misc/update-dist.sh`
takes the place of a release.

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the JBangLite modifications are
provided under the same license. `jbang.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver (Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md)
for details and for the origin of code adapted from other projects.
