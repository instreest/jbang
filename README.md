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

JBangLite is not released as a package: a project installs it into its own
repository and commits it, so anyone who checks the project out can run its
scripts without installing JBangLite — or a JDK — first.

```bash
curl -Ls https://raw.githubusercontent.com/instreest/jbanglite/main/dist/install.sh | bash
git add jbanglite && git commit -m "Add JBangLite"
jbanglite/jbanglite src/Hello.java
```

`install.cmd` does the same on Windows. The installer copies the launchers,
their two bootstrap scripts, `jbanglite.properties`, itself, `LICENSE` and a
`README.md` into `jbanglite/`, and all of it is committed, like a Gradle
wrapper.

**`jbanglite.jar` is not among them.** What a project commits is
`jbanglite.properties`, which pins the version, the download URL and the
SHA-256:

```properties
distributionVersion=0.2.0
distributionUrl=https://github.com/instreest/jbanglite/releases/download/v0.2.0/jbanglite.jar
distributionSha256Sum=db78...
```

On the first run `jbanglite-bootstrap-jar` downloads that jar into
`$JBANGLITE_CACHE_DIR/jbanglite/<version>`, verifies the checksum and keeps it
there. The cache is per machine, so several projects pinning the same version
share one download, and moving to a new version changes three lines rather
than a 2 MB binary. This is what the Gradle and Maven wrappers do, and for the
same reason: a 46 kB set of scripts in a project's history instead of a
binary per update.

A project that would rather not depend on the download can vendor the jar by
dropping a `jbanglite.jar` into `jbanglite/` next to the launcher: it wins over
the properties, and then nothing but a JDK is ever fetched.

## Updating

Which JBangLite a project runs is a property of the project, the way it is for
the Gradle and Maven wrappers: it is what `jbanglite/jbanglite.properties` says,
it is committed, and a user who clones the project gets it. So the tool's author
updates it and commits, and users receive the new version with `git pull`.

```bash
jbanglite/jbanglite --update            # the newest release
jbanglite/jbanglite --update v0.3.0     # or a tag, branch or commit
git add jbanglite && git commit -m "Update JBangLite to 0.3.0"
```

`--update` re-runs the `install.sh` next to it, which replaces every file with
the one from the chosen revision, `jbanglite.properties` included. It is
answered by the launcher script, so it needs neither the jar nor a JDK: an
installation whose pinned jar can no longer be downloaded can still update
itself out of that state. `JBANGLITE_REPO` installs from another fork.

`--version` names the version that will actually run, and under it where that
was decided. Nothing is downloaded:

```
$ jbanglite/jbanglite --version
jbanglite 0.3.0
  pinned 0.3.0 by /home/me/tool/jbanglite/jbanglite.properties
  jar 0.3.0 at /home/me/.jbanglite/cache/jbanglite/0.3.0/jbanglite.jar
```

A vendored jar overrides the pin, so it is the one named on the first line:

```
jbanglite 0.4.0
  pinned 0.3.0 by /home/me/tool/jbanglite/jbanglite.properties
  jar 0.4.0 at /home/me/tool/jbanglite/jbanglite.jar (vendored, so this jar runs and not the pinned version)
```

The cached jar can be named without being opened: the bootstrap script put it
under the version it pinned, and only after its SHA-256 matched. A vendored jar
is asked, by running it, which needs no tool for reading a zip and no download
either, only a JDK that is already on the machine; without one its version is
reported as unknown. Before the first run there is no jar at all and the pinned
version is the answer.

`--update` does not touch a vendored jar and warns that it still wins, rather
than deleting a file the project committed.

There is deliberately no version check on ordinary runs: nothing reaches the
network unless a jar or a JDK is actually missing, and telling users about a
new version is the tool author's job, not JBangLite's.

[`dist/`](dist) in this repository is exactly what a project gets. Releasing
means building the jar, refreshing `dist/` for its version, publishing the jar
as a release asset and committing `dist/`:

```bash
misc/update-dist.sh 0.2.0
gh release create v0.2.0 build/libs/jbanglite.jar
git add dist && git commit -m "Release 0.2.0"
```

`misc/update-dist.sh --check` reports whether the scripts in `dist/` are up to
date with the sources. The jar never enters git, so neither this repository nor
the projects that install JBangLite grow by 2 MB per release.

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
| running a script (`jbanglite [options] <script.java> [args]`), `--version`, `--help` | every subcommand: `run` as a word, `build`, `info`, `jdk`, `edit`, `init`, `alias`, `template`, `catalog`, `trust`, `cache`, `completion`, `wrapper`, `app`, `export`, `config`, `deps`, `version` |
| `.java` sources | `.jsh`, `.kt`, `.groovy`, `.md`, jars and GAVs as scripts |
| local files | remote scripts, gists, catalogs and aliases |
| plain jars | native images and the launchers' native mode, integrations (Quarkus and friends) |
| `dist/`, committed into a project by `install.sh`/`install.cmd` | releases, `tar`/`zip` distributions, installers, packages, the update mechanism, CI |

There are no subcommands: JBangLite does one thing, which is to run the
script, and `--help` and `--version` are the only options that do something
else. The other options are `--verbose`, `--quiet`, `--fresh`, `--offline`,
`--java`, `--main`, `--module`, `--deps`, `--repos`, `-C<compiler option>`,
`-R<jvm option>`, `-Dkey=value`, `--enable-preview`, `-ea`, `-esa` and
`--cds`.

The cache layout is the same as full JBang (`~/.jbanglite/cache/jars/<file>.<hash>/<name>.jar`,
`~/.jbanglite/cache/jdks/<version>`), except that JBangLite never writes
`~/.jbanglite/currentjdk`: running a script installs the JDK it asks for into the
cache and nothing else, so one run never changes which JDK the next one picks.

Options are read getopt style: every option is accepted anywhere before the
script, `--` ends them, and
everything after the script is the script's. The script is a `.java` file, or
`-` to read it from stdin; a path that is readable but not a regular file, such
as a process substitution or a pipe, is read the same way. Relative `//SOURCES`
and `//FILES` of such a script are resolved from the working directory.

```bash
cat Hello.java | jbanglite -
jbanglite <(sed 's/World/JBang/' Hello.java)
```

### How a script is started

Full JBang prints the `java` command line to stdout and exits with status 255,
and its launcher script `eval`s that line. JBangLite does not: it starts the
`java` process itself, as a child that shares stdin, stdout and stderr, and
exits with the script's exit status. So there is no protocol between the jar
and the launchers, no exit code with a special meaning, nothing is captured
and nothing is re-parsed by a shell: `jbanglite Hello.java | sort` streams,
`echo x | jbanglite Hello.java` reaches the script, and `$?` is the script's.
The launchers only find a JDK and `exec` the jar. The price is that the
jbanglite JVM stays around, idle, while the script runs; `JBANGLITE_JAVA_OPTIONS`
tunes that JVM.

### What the launcher scripts need

The launchers (`jbanglite` for POSIX shells, `jbanglite.cmd` for Windows) do
two things: find the jar and find a JDK, then `exec` the one with the other.
They never call a command of the jar and set nothing in its environment.

The jar is a `jbanglite.jar` next to the launcher when a project vendors one,
and otherwise whatever `jbanglite-bootstrap-jar` prints after installing the
version `jbanglite.properties` pins.

Which JVM runs `jbanglite.jar` hardly matters, so that search is deliberately
short:

1. `$JBANGLITE_CACHE_DIR/jdks/bootstrap`
2. `JAVA_HOME`
3. `javac` on the `PATH`, asked for its `java.home` (through
   `javac -J-XshowSettings:properties -version`) so that shims (jenv, SDKMAN,
   the Windows `javapath` stub) lead to the real JDK

Any JDK 11 or newer is accepted; a JRE is not, because scripts have to be
compiled. If nothing is found, the launcher runs `jbanglite-bootstrap-jdk`
(`jbanglite-bootstrap-jdk.cmd` on Windows), which sits next to it, downloads
the newest Temurin 25 into `$JBANGLITE_CACHE_DIR/jdks/bootstrap`, verifies its
published SHA-256 and prints that directory. The bootstrap script is a program
of its own: it can be run by hand, tested alone, or replaced by anything else
that puts a JDK there. The jar prefers the JVM it is running on when
that satisfies a script's `//JAVA`, so a tool that asks for `//JAVA 25` costs
one download on a machine without Java, not two.

Both bootstrap scripts follow the same shape: they print the one path they
found or installed on stdout, say everything else on stderr, verify a SHA-256
before accepting a download, write to a file of their own that is renamed into
place, and take a directory lock so parallel runs wait instead of colliding.

The `.cmd` bootstrap scripts are self-contained — they use the `curl`, `tar`
and `certutil` that Windows ships with, so no PowerShell is involved and there
is no PowerShell launcher in this fork. `jbanglite-bootstrap-jar` needs `curl`
or `wget` and `sha256sum` or `shasum`; `jbanglite-bootstrap-jdk` needs those
plus `unzip` (to read the JVM index) and `tar` with `gzip`; on Windows
shells (Git Bash, MSYS2, Cygwin) `jbanglite` hands over to `jbanglite.cmd`
instead, so nothing but Windows itself is needed there either.

The download uses the very same JVM index the jar uses for `//JAVA`, the
Coursier index published on Maven Central
(`io.get-coursier.jvm.indices:index-<platform>`), so no JDK discovery service is
involved, and `JBANGLITE_JVM_INDEX_BASEURL` points both of them at a corporate mirror.

The JDK a script asks for with `//JAVA` is still provisioned by the jar; the
bootstrap JDK only exists to get `jbanglite.jar` started. `jdk default`, `jdk
install` and `jdk list` were therefore removed, and so was the `currentjdk`
link they managed. There is no native-image mode
either: the launchers only ever run the jar.

### Several runs at once

A build matrix or a multi-module build starts JBangLite many times at once
against the same `~/.jbanglite`, so no download may fail just because another run
got there first:

| | |
| --- | --- |
| the jar and the bootstrap JDK | a directory lock (`mkdir` is atomic) — one run installs, the others wait for it and then use what it installed, giving up after `JBANGLITE_LOCK_TIMEOUT` seconds with a message naming the lock to remove |
| the JVM index, every archive and unpack directory | a file of this run's own, renamed into place when it is complete; whoever gets there first wins and the loser keeps that copy |

The JDKs the jar installs for `//JAVA` are locked by the jar itself, so the two
mechanisms do not overlap.

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
and the jspecify annotations used by the mirrored files. Maven Resolver's own
HTTP transport (Apache HttpClient, Gson and the public suffix list, a third of
the jar) is left out: `JdkHttpTransporterFactory` does the same job on the
JDK's `java.net.http.HttpClient`, with the credentials and proxies from
`~/.m2/settings.xml` that the resolver hands it, and without uploads, which
JBangLite never makes. JDK download/unpacking, class-file inspection for the
main class, jar creation, OS detection and module-info generation are
implemented with the JDK's standard library only. `jbanglite.jar` itself needs Java 11 or later to run (JBang targets
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
downloaded unless they are asked for explicitly with `JBANGLITE_JDK_DISTRO`.

Requesting a full version such as `//JAVA 25.0.3` installs and uses exactly that
version, which is what to use when the result must be reproducible. A request
like `25` or `25+` accepts any matching patch release.

## Environment variables

| Variable | Meaning |
| --- | --- |
| `JBANGLITE_DIR` | base directory (default `~/.jbanglite`) |
| `JBANGLITE_CACHE_DIR` | cache directory (default `$JBANGLITE_DIR/cache`) |
| `JBANGLITE_MAVEN_REPO` | local Maven repository to use instead of `~/.m2/repository` |
| `JBANGLITE_DEFAULT_JAVA_VERSION` | JDK version to install when the script does not specify one (default 17) |
| `JBANGLITE_JVM_INDEX_BASEURL` | Maven repository to read the JVM index from (default `https://repo1.maven.org/maven2`) |
| `JBANGLITE_JDK_DISTRO` | distributions to install from, most preferred first (default `temurin`) |
| `JBANGLITE_JDK_INDEX` | path to a JDK index JSON file, or a Maven coordinate, replacing the default index |
| `JBANGLITE_DOWNLOAD_RETRY` | extra download attempts (default 5, `0` disables retries) |
| `JBANGLITE_DOWNLOAD_RETRY_DELAY` | seconds between attempts (default `0`, meaning exponential backoff) |
| `JBANGLITE_LOCK_TIMEOUT` | seconds to wait for another run that is downloading (default 600) |
| `JBANGLITE_DIST_URL` | where to fetch `jbanglite.jar` from, overriding the `distributionUrl` in `jbanglite.properties` |

## Building

```bash
./gradlew assemble
```

produces `build/libs/jbanglite.jar` (self-contained). Pass `-PjbangVersion=x.y.z`
to set the version; `misc/update-dist.sh <version>` does so and writes the
matching `dist/jbanglite.properties`.

`./gradlew test` runs the test suite: the mirrored `TestDirectives` from JBang
and functional tests for the launcher scripts and the installer.

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs that same command on
`ubuntu-latest` and `windows-latest` for every push. Windows is why it exists:
the launcher and the two bootstrap scripts are written twice, once for POSIX
shells and once for `cmd.exe`, and `TestWindowsLaunchers` and
`TestWindowsWrapperInstall` are `@EnabledOnOs(WINDOWS)`, so they are skipped in
silence on any other machine. The Windows tests install from a local server the
way `install.cmd` installs from GitHub, which serves the scripts with the LF
line endings git stores, so they also answer whether `cmd.exe` runs the scripts
as a project actually receives them.

There is no release pipeline: publishing is `misc/update-dist.sh <version>` and
a release with the jar attached.

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the JBangLite modifications are
provided under the same license. `jbanglite.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver (Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md)
for details and for the origin of code adapted from other projects.
