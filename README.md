# JBangLite

**Ship a Java tool that anyone can run straight after `git clone`.** Commit a
`jbanglite/` directory next to your tool, and whoever checks the project out
runs it with one command. No JDK to install, no dependencies to fetch by hand,
no build to explain.

```bash
jbanglite/jbanglite tools/Report.java --since 2026-01
```

That works because the tool declares what it needs, in the tool:

```java
//JAVA 21+
//DEPS org.apache.commons:commons-csv:1.12.0
//SOURCES report/*.java
```

JBangLite reads those directives, installs a JDK if the machine has none,
resolves the dependencies from Maven Central, compiles, and runs. Every
download is pinned to a version and checked against a SHA-256 committed with
your project, and is fetched once per machine, so the second tool and the
second checkout cost nothing.

The directives are JBang's, parsed by JBang's own parser. JBangLite is a
reduced fork of [JBang](https://github.com/jbangdev/jbang) that does this one
thing. It is not affiliated with, endorsed by, or supported by the JBang
project; please report problems here, not to them.

## Quick start

For the tool's author, once:

```bash
curl -fsSL https://github.com/instreest/jbanglite/releases/latest/download/install.sh | bash
git add jbanglite && git commit -m "Add JBangLite"
```

For everyone else, nothing. Put this in your project's README:

````markdown
## Running the tools

```bash
jbanglite/jbanglite tools/Report.java      # macOS, Linux, WSL
jbanglite\jbanglite.cmd tools\Report.java  # Windows
```
````

`install.cmd` installs from a Windows command prompt. The installer writes
eleven files, about 50 kB, into `jbanglite/`; commit all of them, the way a
Gradle or Maven wrapper is committed.

## What gets committed, and what gets downloaded

`jbanglite/` holds the launcher scripts and `jbanglite.properties`, which pins
everything a run may need to fetch:

```properties
distributionVersion=0.2.0
distributionUrl=https://github.com/instreest/jbanglite/releases/download/v0.2.0/jbanglite.jar
distributionSha256Sum=dbc5414a...
bootstrapJdkVersion=25.0.3
bootstrapJdkUrl.linux-amd64=https://github.com/adoptium/...tar.gz
bootstrapJdkSha256Sum.linux-amd64=69264a7a...
```

Neither `jbanglite.jar` nor a JDK is committed. On the first run the launcher
downloads what it is missing into `~/.jbanglite`, checks it against the SHA-256
above, and keeps it there for every project on the machine. So a project's
history carries scripts, not binaries, and moving to a new version changes a
few lines rather than megabytes.

A project that would rather not depend on the download can put a
`jbanglite.jar` into `jbanglite/` itself: it wins over the properties, and then
only a JDK is ever fetched.

## Updating

Which JBangLite a project runs is a property of the project, as it is for the
Gradle and Maven wrappers: it is what `jbanglite/jbanglite.properties` says, it
is committed, and whoever clones the project gets it. The tool's author
updates it; everyone else receives it with `git pull`.

```bash
jbanglite/jbanglite --update            # the newest release
jbanglite/jbanglite --update v0.3.0     # or a particular one
git add jbanglite && git commit -m "Update JBangLite to 0.3.0"
```

`--update` re-runs the installer next to it and replaces every file. It is
answered by the launcher script, so it needs neither the jar nor a JDK: an
installation whose pinned jar can no longer be downloaded can still update
itself out of that state. It leaves a vendored `jbanglite.jar` alone, and warns
that the old jar still wins.

`--version` names the version that will actually run, and downloads nothing:

```
$ jbanglite/jbanglite --version
jbanglite 0.3.0
  pinned 0.3.0 by /home/me/tool/jbanglite/jbanglite.properties
  jar 0.3.0 at /home/me/.jbanglite/cache/jbanglite/0.3.0/jbanglite.jar
```

Ordinary runs never check for a new version. Nothing reaches the network
unless a jar or a JDK is actually missing.

## Directives

What a script needs is declared in the script. These are applied:

| Directive | Behaviour |
| --- | --- |
| `//JAVA <version>[+]` | the JDK to build and run with, installed when the machine has none |
| `//DEPS <gav>` | resolved from Maven Central; `@pom` entries act as BOMs |
| `//SOURCES <file-or-glob>` | compiled together with the script, recursively |
| `//FILES [<target>=]<file-or-glob>` | copied into the jar, optionally under another name |
| `//REPOS [<id>=]<url-or-alias>` | extra Maven repositories |
| `//MAIN <class>` | the class to run, when there is more than one `main` |
| `//COMPILE_OPTIONS`, `//JAVAC_OPTIONS` | passed to `javac` |
| `//RUNTIME_OPTIONS`, `//JAVA_OPTIONS` | passed to `java` |
| `//MANIFEST <key>=<value>` | added to the jar manifest; `Add-Opens`, `Add-Exports` and `Enable-Native-Access` also reach the `java` command line |
| `//PREVIEW` | compiles and runs with `--enable-preview` |
| `${property}` in any directive | system properties, `-Dkey=value` and `os.detected.*` |

Every other directive JBang defines is parsed and ignored, so a script written
for JBang still runs: `//MODULE`, `//CDS`, `//JAVAAGENT`, `//GAV`,
`//DESCRIPTION`, `//DOCS`, `//NOINTEGRATIONS`, `//NATIVE_OPTIONS`, `//GROOVY`,
`//KOTLIN`, and `//DEPS` naming a `.java` file.

## Options

There are no subcommands. JBangLite runs the script, and only `--help`,
`--version` and `--update` do something else.

```
jbanglite [<options>] <script.java> [<args>...]
```

| Option | |
| --- | --- |
| `-h`, `--help` | print the help and exit |
| `-V`, `--version` | print the version and exit |
| `--update [<ref>]` | update this installation and exit |
| `--verbose` | print what is being done |
| `--quiet` | only print errors |
| `--fresh` | ignore the caches and rebuild |
| `-o`, `--offline` | never access the network |
| `-Dkey=value` | a system property, for `${...}` in directives and for the script |
| `-R<option>` | an extra JVM option for the script |
| `-y`, `--yes` | download what is missing without asking |

Options may appear anywhere before the script, `--` ends them, and everything
after the script is the script's. There is deliberately no option that
overrides what a script's directives say: the tool's author decides what the
tool needs, not whoever runs it.

## Environment

| Variable | |
| --- | --- |
| `JBANGLITE_CONFIRM_DOWNLOADS` | whether a download is confirmed first: `auto` (default), `always`, `never` |
| `JBANGLITE_ASSUME_YES` | set to anything to answer yes in advance, like `--yes` |
| `JBANGLITE_DIR` | base directory (default `~/.jbanglite`) |
| `JBANGLITE_CACHE_DIR` | cache directory (default `$JBANGLITE_DIR/cache`) |
| `JBANGLITE_MAVEN_REPO` | local Maven repository to use instead of `~/.m2/repository` |
| `JBANGLITE_DEFAULT_JAVA_VERSION` | JDK to use when a script names none (default 17) |
| `JBANGLITE_JDK_INDEX` | read the JVM index from here instead of from Maven Central |
| `JBANGLITE_JAVA_OPTIONS` | JVM options for JBangLite itself |
| `JBANGLITE_DOWNLOAD_RETRY` | extra download attempts (default 5, `0` disables retries) |
| `JBANGLITE_DOWNLOAD_RETRY_DELAY` | seconds between attempts (default `0`, meaning exponential backoff) |
| `JBANGLITE_LOCK_TIMEOUT` | seconds to wait for another run that is downloading (default 600) |
| `JBANGLITE_DIST_URL` | fetch `jbanglite.jar` from here instead of from the pinned URL |
| `JBANGLITE_REPO`, `JBANGLITE_REF` | the repository and release the installer installs from |
| `JBANGLITE_DIST_BASEURL` | install from here instead of from a GitHub release |

Everything JBangLite writes goes under `JBANGLITE_DIR`; nothing is written into
the project. Several runs at once are safe: each download is taken by one run
while the others wait, and every file is renamed into place only once it is
complete, so a build matrix never trips over a half-written file.

## Downloads ask first

JBangLite fetches three kinds of thing: its own jar, a JDK to run that jar with,
and the dependencies a script declares. None of them happens silently. When
something has to be fetched, JBangLite says what, and on a terminal it asks.

```
JBangLite has to download:
  - jbanglite.jar 0.2.0
  - a JDK to run it with (Temurin 25.0.3); this machine has none

Continue? [Y/n]:
```

The question only appears when a download really would happen. A JDK that is
already installed never reaches it, and neither does a dependency that is
already in the local Maven repository: before asking, JBangLite resolves the
coordinates against `~/.m2/repository` alone, and when that succeeds there is
nothing to ask about. So this is a first-run question, not a per-run one.
`--fresh` skips that shortcut, because it exists to go to the remote
repositories again.

A cold first run asks twice. The launcher asks about the jar and the JDK, which
it fetches itself before any JVM exists; the jar asks about the dependencies,
which only it knows about. Each question names bytes that are really about to
move. `--update` asks before replacing an installation.

| | |
| --- | --- |
| `JBANGLITE_CONFIRM_DOWNLOADS=auto` (default) | ask when there is a terminal; otherwise say what is being fetched and go ahead, so an unattended build is never left waiting for an answer nobody is there to give |
| `JBANGLITE_CONFIRM_DOWNLOADS=always` | ask, and fetch nothing when there is no terminal. This is the setting for a machine that is meant to stay off the network, and for a project that means to bring everything it needs with it |
| `JBANGLITE_CONFIRM_DOWNLOADS=never`, `JBANGLITE_ASSUME_YES=1`, `--yes` | never ask |

Answering with Enter accepts: the gate is there to say what is about to happen,
not to make every first run fail.

"A terminal" means one that can actually be opened, `/dev/tty` or `CON`. It is
not `System.console()`: since Java 22 that is non-null even when stdin is a
pipe, so it would report a terminal where there is none and then read the answer
out of the script's own input. The question and its answer never touch stdout,
so `jbanglite Hello.java | sort` is unaffected, and neither is a script that
reads its own stdin.

```yaml
- run: jbanglite/jbanglite tools/Report.java
  env:
    JBANGLITE_CONFIRM_DOWNLOADS: never
```

## Requirements

A machine needs a POSIX shell with `curl` or `wget`, `sha256sum` or `shasum`,
and `tar` with `gzip`; or, on Windows, nothing that Windows does not already
ship. A JDK 11 or newer is used if there is one, and installed if there is not.
Alpine (musl) is the exception: install a JDK there yourself.

## Contributing and internals

How JBangLite is built, released and kept in step with JBang is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the JBangLite modifications are
provided under the same license. `jbanglite.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver, Apache HttpClient, Apache Commons Compress and Gson
(Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md) for details
and for the origin of code adapted from other projects.
