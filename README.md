# jkite

**Ship a Java tool that anyone can run straight after `git clone`.** Commit a
`jkite/` directory next to your tool, and whoever checks the project out
runs it with one command.

They do not need a JDK installed. They do not need *the* JDK your tool asks
for, even if the one they have is older or newer. They do not fetch your
dependencies, and there is no build to explain. None of that is their problem
any more, and none of it is yours to support.

```bash
jkite/jkite tools/Report.java --since 2026-01
```

That works because the tool declares what it needs, in the tool:

```java
//JAVA 21+
//DEPS org.apache.commons:commons-csv:1.12.0
//SOURCES report/*.java
```

jkite reads those directives, installs a JDK if the machine has none,
resolves the dependencies from Maven Central, compiles, and runs. Every
download is pinned to a version and checked against a SHA-256 committed with
your project, and is fetched once per machine, so the second tool and the
second checkout cost nothing.

The directives are JBang's, parsed by JBang's own parser. jkite is a
reduced fork of [JBang](https://github.com/jbangdev/jbang) that does this one
thing. It is not affiliated with, endorsed by, or supported by the JBang
project; please report problems here, not to them.

## Quick start

For the tool's author, once:

```bash
curl -fsSL https://github.com/instreest/jkite/releases/latest/download/install.sh | bash
git add jkite && git commit -m "Add jkite"
```

For everyone else, nothing. Put this in your project's README:

````markdown
## Running the tools

```bash
jkite/jkite tools/Report.java      # macOS, Linux, WSL
jkite\jkite.cmd tools\Report.java  # Windows
```
````

`install.cmd` installs from a Windows command prompt. The installer writes
eleven files, about 80 kB, into `jkite/`; commit all of them, the way a
Gradle or Maven wrapper is committed.

## What gets committed, and what gets downloaded

`jkite/` holds the launcher scripts and `jkite.properties`, which pins
everything a run may need to fetch:

```properties
distributionVersion=0.2.0
distributionUrl=https://github.com/instreest/jkite/releases/download/v0.2.0/jkite.jar
distributionSha256Sum=dbc5414a...
bootstrapJdkVersion=25.0.3
bootstrapJdkUrl.linux-amd64=https://github.com/adoptium/...tar.gz
bootstrapJdkSha256Sum.linux-amd64=69264a7a...
```

Neither `jkite.jar` nor a JDK is committed. On the first run the launcher
downloads what it is missing into `~/.jkite`, checks it against the SHA-256
above, and keeps it there for every project on the machine. So a project's
history carries scripts, not binaries, and moving to a new version changes a
few lines rather than megabytes.

A project that would rather not depend on the download can put a
`jkite.jar` into `jkite/` itself: it wins over the properties, and then
only a JDK is ever fetched.

## Updating

Which jkite a project runs is a property of the project, as it is for the
Gradle and Maven wrappers: it is what `jkite/jkite.properties` says, it
is committed, and whoever clones the project gets it. The tool's author
updates it; everyone else receives it with `git pull`.

```bash
jkite/jkite --update            # the newest release
jkite/jkite --update v0.3.0     # or a particular one
git add jkite && git commit -m "Update jkite to 0.3.0"
```

`--update` re-runs the installer next to it and replaces every file. It is
answered by the launcher script, so it needs neither the jar nor a JDK: an
installation whose pinned jar can no longer be downloaded can still update
itself out of that state. It leaves a vendored `jkite.jar` alone, and warns
that the old jar still wins.

`--version` names the version that will actually run, and downloads nothing:

```
$ jkite/jkite --version
jkite 0.3.0
  pinned 0.3.0 by /home/me/tool/jkite/jkite.properties
  jar 0.3.0 at /home/me/.jkite/cache/jkite/0.3.0/jkite.jar
```

Ordinary runs never check for a new version. Nothing reaches the network
unless a jar or a JDK is actually missing.

## Directives

What a script needs is declared in the script. These are applied:

| Directive | Behaviour |
| --- | --- |
| `//JAVA <version>[+]` | the JDK to build and run with, installed when the machine has none |
| `//DEPS <gav>` | resolved from Maven Central; `@pom` entries act as BOMs |
| `//SOURCES <file-or-glob>` | compiled together with the script, recursively; every other source it needs is named here |
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

There are no subcommands. jkite runs the script, and only `--help`,
`--version` and `--update` do something else.

```
jkite [<options>] <script.java> [<args>...]
```

| Option | |
| --- | --- |
| `-h`, `--help` | print the help and exit |
| `-V`, `--version` | print the version and exit |
| `--update [<ref>]` | update this installation and exit |
| `--verbose` | print what is being done |
| `--quiet` | only print errors |
| `--fresh` | ignore the caches and rebuild |
| `--clear-cache` | remove the built jars and resolved dependencies, and exit |
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
| `JKITE_CONFIRM_DOWNLOADS` | whether a download is confirmed first: `auto` (default), `always`, `never` |
| `JKITE_ASSUME_YES` | set to anything to answer yes in advance, like `--yes` |
| `JKITE_DIR` | base directory (default `~/.jkite`) |
| `JKITE_CACHE_DIR` | cache directory (default `$JKITE_DIR/cache`) |
| `JKITE_MAVEN_REPO` | local Maven repository to use instead of `~/.m2/repository` |
| `JKITE_DEFAULT_JAVA_VERSION` | JDK to use when a script names none (default 17) |
| `JKITE_JDK_INDEX` | read the JVM index from here instead of from Maven Central |
| `JKITE_JAVA_OPTIONS` | JVM options for jkite itself |
| `JKITE_DOWNLOAD_RETRY` | extra download attempts (default 5, `0` disables retries) |
| `JKITE_DOWNLOAD_RETRY_DELAY` | seconds between attempts (default `0`, meaning exponential backoff) |
| `JKITE_LOCK_TIMEOUT` | seconds to wait for another run that is downloading (default 600) |
| `JKITE_DIST_URL` | fetch `jkite.jar` from here instead of from the pinned URL |
| `JKITE_REPO`, `JKITE_REF` | the repository and release the installer installs from |
| `JKITE_DIST_BASEURL` | install from here instead of from a GitHub release |

Everything jkite writes goes under `JKITE_DIR`; nothing is written into
the project. Several runs at once are safe: each download is taken by one run
while the others wait, and every file is renamed into place only once it is
complete, so a build matrix never trips over a half-written file.

## Downloads ask first

jkite fetches three kinds of thing: its own jar, a JDK to run that jar with,
and the dependencies a script declares. When something has to be fetched it says
what, and on a terminal it asks.

```
jkite has to download:
  - jkite.jar 0.2.0
  - a JDK to run it with (Temurin 25.0.3); this machine has none

Continue? [Y/n]:
```

Only a download that would really happen is asked about, so this is a first-run
question rather than a per-run one. A JDK that is already installed never
reaches it, and neither does a dependency already in the local Maven repository.
A cold first run asks twice: the launcher about the jar and the JDK, the jar
about the dependencies. `--update` asks before replacing an installation.

| | |
| --- | --- |
| `JKITE_CONFIRM_DOWNLOADS=auto` (default) | ask on a terminal; otherwise say what is being fetched and go ahead, so an unattended build never waits for an answer nobody is there to give |
| `JKITE_CONFIRM_DOWNLOADS=always` | ask, and fetch nothing when there is no terminal. The setting for a machine meant to stay off the network |
| `JKITE_CONFIRM_DOWNLOADS=never`, `JKITE_ASSUME_YES=1`, `--yes` | never ask |

Enter accepts. The question and its answer go to the terminal, never to stdout,
so a pipeline built on a tool's output is unaffected.

```yaml
- run: jkite/jkite tools/Report.java
  env:
    JKITE_CONFIRM_DOWNLOADS: never
```

## Requirements

A machine needs a POSIX shell with `curl` or `wget`, `sha256sum` or `shasum`,
and `tar` with `gzip`; or, on Windows, nothing that Windows does not already
ship. A JDK 11 or newer is used if there is one, and installed if there is not.
Alpine (musl) is the exception: install a JDK there yourself.

## How it works, and contributing

What happens between `git clone` and the tool's first line of output, in three
diagrams: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). How jkite is built,
released and kept in step with JBang: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
What is welcome and what is not, and under which license a contribution
arrives: [CONTRIBUTING.md](CONTRIBUTING.md).

Found a security problem? Report it privately, not in an issue:
[SECURITY.md](SECURITY.md).

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the jkite modifications are
provided under the same license, and so is every contribution made to it. `jkite.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver, Apache HttpClient, Apache Commons Compress and Gson
(Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md) for details
and for the origin of code adapted from other projects.
