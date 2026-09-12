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

Options may appear anywhere before the script, `--` ends them, and everything
after the script is the script's. There is deliberately no option that
overrides what a script's directives say: the tool's author decides what the
tool needs, not whoever runs it.

## Environment

| Variable | |
| --- | --- |
| `JBANGLITE_DIR` | base directory (default `~/.jbanglite`) |
| `JBANGLITE_CACHE_DIR` | cache directory (default `$JBANGLITE_DIR/cache`) |
| `JBANGLITE_MAVEN_REPO` | local Maven repository to use instead of `~/.m2/repository` |
| `JBANGLITE_DEFAULT_JAVA_VERSION` | JDK to use when a script names none (default 17) |
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

## Requirements

A machine needs a POSIX shell with `curl` or `wget`, `sha256sum` or `shasum`,
and `tar` with `gzip`; or, on Windows, nothing that Windows does not already
ship. A JDK 11 or newer is used if there is one, and installed if there is not.
Alpine (musl) is the exception: install a JDK there yourself.

## Contributing and internals

How JBangLite is built, released and kept in step with JBang is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

MIT, Copyright (c) 2020 Max Rydahl Andersen; the original JBang notice is kept
unchanged in [LICENSE](LICENSE) and the JBangLite modifications are under the
same license. `jbanglite.jar` bundles Maven Resolver and others; see
[THIRD-PARTY.md](THIRD-PARTY.md).
