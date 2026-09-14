# Developing JKite

The shape of a run, and where the network is touched, is in
[ARCHITECTURE.md](ARCHITECTURE.md). This file is about working on the code.

## Building

```bash
./gradlew build
```

produces `build/libs/jkite.jar`, self-contained, and runs the tests.
`-PjkiteVersion=x.y.z` sets the version the jar reports.

The jar targets Java 11 and is built with whatever JDK Gradle runs on.

A project installing JKite pins the jar by SHA-256, so the build that
produces that jar is pinned to the same degree:

| | |
| --- | --- |
| `gradle.lockfile` | every resolved version, transitive ones included. A dependency that changes under us fails the build instead of ending up in a release |
| `gradle/verification-metadata.xml` | the SHA-256 of every artifact the build downloads, the Gradle plugins included |

Both are regenerated together after a version change:

```bash
./gradlew --refresh-dependencies --write-locks --write-verification-metadata sha256 build
```

`--refresh-dependencies` matters: without it, artifacts that are already in the
Gradle cache are not resolved again and so are left out of the checksums.

Releasing is by hand, through `misc/update-dist.sh`; what the tests cover is
under [Tests and CI](#tests-and-ci).

## Releasing

```bash
misc/update-dist.sh 0.3.0
gh release create v0.3.0 dist/* build/libs/jkite.jar
git add dist && git commit -m "Release 0.3.0"
```

`misc/update-dist.sh` copies the launcher scripts into `dist/`, builds the jar,
and writes `dist/jkite.properties`: the version, URL and SHA-256 of the jar,
and the same three for the Temurin JDK of every platform a checkout may be on.
It reads those from the [Coursier](https://github.com/coursier/jvm-index) JVM
index on Maven Central, once, here. The scripts a project commits therefore
have nothing to resolve and nothing to parse; they download, verify and unpack.

`dist/` is what a project installs and is also what the release carries as
assets, which is why `gh release create` uploads it: `install.sh` fetches from
`releases/latest/download/`, so the newest release is found through GitHub's
redirect without an API call.

`misc/update-dist.sh --check` reports whether the scripts in `dist/` still
match the sources. `TestWrapperInstall` asserts the same thing, so a forgotten
refresh fails the build.

Temurin publishes no Windows ARM64 build of Java 25, so `windows-arm64` is
pinned to the x64 archive, which Windows on ARM runs under emulation. This JDK
only has to start `jkite.jar`; the JDK a script asks for with `//JAVA` is
installed by the jar. Drop the mapping in `update-dist.sh` once Temurin ships
one.

## Staying in step with JBang

JBang sits behind one interface, `DirectiveParser` in `dev.jbang.spi`, which
turns a source file into a `SourceDirectives` built from JKite's own types.
`MirroredDirectiveParser` is the implementation in use and the only class that
names `Directives` and `KeyValue`, so the mirrored parser is an implementation
detail rather than JKite's API. `TestDirectiveParser` states the contract on
the interface alone, so a second implementation is held to the same test and
wired in at `Providers`, with nothing above the interface changing.

JBang publishes its own code as `dev.jbang:jbang.bin` with every release, and
its `Directives` is identical to the copy here, member for member. Depending on
it instead of mirroring is therefore possible, and is deliberately not done: the
artifact brings the whole CLI's dependency tree - aesh, tamboui, jsoup, qute and
the rest - where the parse path needs none of it, and this fork does not trim
dependency trees by hand. Copying whole files costs less. What would change that
is the sync becoming expensive in practice, not the artifact appearing;
`misc/library-boundary-analysis.md` records the reasoning.

Below that interface the tree is in three parts, so that JBang's fixes to the
directive handling can be taken over without merging:

| | Contents | Maintenance |
| --- | --- | --- |
| Mirror | the files in `misc/upstream-mirror.txt`, among them `Directives.java` and its test | copied from JBang unchanged, never edited here |
| Shims | the files in `misc/upstream-shims.txt` (`Util`, `JavaUtil`, `DependencyUtil`) | upstream's API with a reduced implementation, checked by hand when upstream changes them |
| JKite | everything else | this fork's own code |

```bash
misc/sync-upstream.sh            # take the mirrored files from upstream/main
misc/sync-upstream.sh <ref>      # ... or from a tag or commit
./gradlew build
```

The script reports which upstream commits touched the mirrored files and which
touched the shims, and records the synced revision in `misc/upstream-ref.txt`.
Because it only copies whole files, an upstream commit that also changes
hundreds of unrelated files costs nothing here.

The Java packages are `dev.jbang.*`, which is upstream's namespace. They stay
that way because the mirrored files are copied byte for byte and reference each
other by package; renaming them would mean editing every sync.

## Tests and CI

```bash
./gradlew test
```

runs JBang's own `TestDirectives`, the contract tests that hold any
`DirectiveParser` and the download gate to the same behaviour, unit tests for
the pieces JKite wrote (the JVM index, archive unpacking, placeholder
expansion), and functional tests that run the launcher scripts and the installer
against a local server.

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs the same command
on `ubuntu-latest` and `windows-latest` for every push. The launcher and the two
bootstrap scripts exist twice, once for POSIX shells and once for `cmd.exe`, and
`TestWindowsLaunchers` and `TestWindowsWrapperInstall` are
`@EnabledOnOs(WINDOWS)`: without the Windows job they are skipped in silence.
Those tests install from a local server exactly as `install.cmd` installs from a
release, and git stores the scripts with LF endings, so they also answer whether
`cmd.exe` runs the scripts as a project actually receives them.

## How a run works

The launcher does two things and then gets out of the way:

1. **Find the jar.** A `jkite.jar` next to the launcher if the project
   vendored one, otherwise the one `jkite-bootstrap-jar` installs from the
   pinned URL into `$JKITE_CACHE_DIR/jkite/<version>`.
2. **Find a JDK.** In order: the bootstrap JDK from an earlier run, `JAVA_HOME`,
   `javac` on the `PATH`. A JDK 11 or newer is required; a JRE is not enough,
   because scripts are compiled. `javac` is asked for its own `java.home`
   (`javac -J-XshowSettings:properties -version`) so that a shim (jenv, SDKMAN,
   the Windows `javapath` stub) leads to the real JDK. If none is found,
   `jkite-bootstrap-jdk` installs the pinned Temurin.
3. **`exec` the jar with that JDK.** The jar then starts the script as a child
   process sharing stdin, stdout and stderr, and exits with the script's status.
   Nothing is captured and nothing is re-parsed by a shell, so
   `jkite Tool.java | sort` streams and `$?` is the script's. The price is
   that the JKite JVM stays around, idle, while the script runs;
   `JKITE_JAVA_OPTIONS` tunes it.

Both bootstrap scripts have the same shape: they print the one path they found
or installed on stdout, say everything else on stderr, verify a SHA-256 before
accepting a download, write to a file of their own that is renamed into place,
and take a directory lock (`mkdir` is atomic) so parallel runs wait instead of
colliding. Each can be run by hand, tested on its own, or replaced by anything
else that puts a jar or a JDK where the launcher looks.

The `.cmd` scripts use only `curl`, `tar` and `certutil`, which Windows ships;
no PowerShell is involved. `jkite` hands over to `jkite.cmd` on Windows
shells (Git Bash, MSYS2, Cygwin), so nothing else is needed there either.

Both sides ask before they download, and both decide whether there is anyone to
ask by opening the terminal itself, `/dev/tty` or `CON`. `System.console()` is
not used for this: since Java 22 it is non-null even when stdin is a pipe, so it
would report a terminal where there is none and then read the answer out of the
script's own input. The shell has the mirror image of the same problem, because
Git Bash hands every process a `/dev/tty` whose read ends at once; a read that
ends without an answer is therefore treated as having nobody to ask.

## How the jar gets JDKs for `//JAVA`

A JDK already on the machine is preferred. Only when none satisfies the request
does the jar download one, and it needs no JDK discovery service to do it:

1. The list of downloadable JDKs is the Coursier JVM index, published on Maven
   Central as `io.get-coursier.jvm.indices:index-<platform>`. It is fetched with
   the same Maven Resolver used for `//DEPS`, so mirrors, proxies and
   credentials from `~/.m2/settings.xml` apply and it is cached in the local
   repository.
2. The archive is downloaded over https from the distributor and its SHA-256
   verified against the checksum published next to it. Redirects off https are
   refused.
3. The result is unpacked into a temporary directory, validated, and moved into
   place under a lock, so parallel runs wait rather than download twice.

Eclipse Temurin is the only distribution used. Requesting a full version such as
`//JAVA 25.0.3` installs exactly that, which is what to use when a build must be
reproducible; `25` or `25+` accepts any matching release.

## Dependencies

`jkite.jar` bundles Maven Resolver through
[MIMA](https://github.com/maveniverse/mima), with the HTTP transport Maven
itself ships; Commons Compress for the JDK archives; Gson for the JVM index; the
slf4j no-op binding the resolver needs; and the jspecify annotations the
mirrored files use.

Each is there for the same reason: the format or the protocol is defined
elsewhere, so an implementation of our own that is subtly wrong writes a wrong
file or makes a wrong request instead of failing.

| | What it carries |
| --- | --- |
| `maven-resolver-transport-http` | the checksum, retry, redirect and authentication behaviour the rest of the Maven ecosystem is tested against |
| `commons-compress` | tar and zip as real JDK archives use them: pax and GNU extensions, links, permissions |
| `gson` | the JVM index, and it arrives with the transport anyway |

Nothing is excluded from what they bring. A dependency tree trimmed by hand is
one that fails in the path nobody tested, and the jar is downloaded once per
machine into a shared cache, so its 6 MB buys more than it costs.

Class-file inspection for the main class, jar creation and OS detection use the
JDK's standard library only. `jkite.jar` needs Java 11 or later to run
(JBang targets Java 8); the JDK a script runs on is whatever `//JAVA` asks for.

`misc/licenses/` holds the licence texts of the bundled libraries, which the
shadow transformers do not carry over; the build copies them into
`META-INF/licenses/` in the jar. See [THIRD-PARTY.md](../THIRD-PARTY.md).
