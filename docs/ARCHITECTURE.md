# How jkite works

Two programs, one boundary. A shell script finds a JDK and a jar; the jar
builds and runs the tool. Everything below is what happens between
`git clone` and the tool's first line of output.

## What is committed and what is fetched

A project commits scripts. Binaries are fetched once per machine and shared by
every project that pins the same version.

```mermaid
flowchart LR
  subgraph repo["the tool's repository (git)"]
    L["jkite/<br/>11 scripts + jkite.properties<br/>79 kB"]
    T["tools/Report.java<br/>//JAVA //DEPS //SOURCES"]
  end
  subgraph machine["~/.jkite (per machine, shared)"]
    J["cache/jkite/&lt;version&gt;/jkite.jar"]
    B["cache/jdks/bootstrap"]
    S["cache/jdks/&lt;version&gt;"]
    A["cache/jars/&lt;name&gt;.&lt;hash&gt;/"]
  end
  M["~/.m2/repository"]
  L -- "pins version + SHA-256" --> J
  L -- "pins version + SHA-256" --> B
  T --> A
  T -- "//DEPS" --> M
  T -- "//JAVA" --> S
```

`jkite.properties` is the only file that decides what may be downloaded: it
carries the version, URL and SHA-256 of the jar, and the same three for the
bootstrap JDK of every platform. A download whose hash does not match is
discarded.

## One run, end to end

```mermaid
sequenceDiagram
  autonumber
  actor U as user
  participant L as jkite<br/>shell or cmd
  participant BS as bootstrap<br/>scripts
  participant JAR as jkite.jar
  participant SC as the tool

  U->>L: jkite/jkite tools/Report.java --since 2026-01
  Note over L: --version and --update<br/>are answered here and stop
  L->>L: is jkite.jar here?
  L->>L: is a usable JDK here?<br/>bootstrap → JAVA_HOME → javac on PATH
  alt something is missing
    L->>U: names it and asks
    U-->>L: yes
    L->>BS: fetch
    BS->>BS: https only, verify SHA-256,<br/>unpack, rename into place
  end
  L->>JAR: exec java -jar, replacing the shell
  JAR->>JAR: read directives through DirectiveParser
  JAR->>JAR: resolve //DEPS against ~/.m2 first
  alt dependencies are missing
    JAR->>U: names them and asks
    U-->>JAR: yes
    JAR->>JAR: Maven Resolver → Maven Central
  end
  JAR->>JAR: find or install the //JAVA JDK
  JAR->>JAR: javac, then package a jar<br/>named by a hash of the sources
  JAR->>SC: start as a child process
  SC-->>U: stdout, stderr, exit code
```

A second run reaches none of the `alt` branches: the jar, the JDKs, the
dependencies and the built jar are all cached, so nothing is asked and nothing
is fetched.

## Where the network is touched

Four places, and no others.

| What | From | Verified against |
| --- | --- | --- |
| `jkite.jar` | the pinned URL | SHA-256 in `jkite.properties` |
| the bootstrap JDK | the pinned URL | SHA-256 in `jkite.properties` |
| the `//JAVA` JDK | the Coursier JVM index, then the distributor | the checksum the distributor publishes, and the URL against the distribution's own account |
| `//DEPS` | Maven Central, or `//REPOS` | Maven Resolver's own checksums |

The first two happen in the shell, before any JVM exists; the last two happen
in the jar. Each side asks before it fetches, which is why a cold first run
asks twice. `JKITE_CONFIRM_DOWNLOADS` governs both.

A checksum is checked when something is downloaded and, where it can be
afforded, whenever what was downloaded is used again: the launcher hashes
`jkite.jar` on every run, and a resolved dependency is kept only while its size
and digest still match. A JDK is too big for that - a few hundred megabytes over
tens of thousands of files - so it is recorded rather than re-checked: what was
downloaded and what its SHA-256 was goes into `.jkite-install` inside the JDK's
own directory, which `--verbose` reads back.

## Inside the jar

```mermaid
flowchart TD
  M["Main<br/>parse options"] --> P["Project<br/>apply directives"]
  P -->|"DirectiveParser, the SPI"| MP["MirroredDirectiveParser<br/>→ JBang's Directives"]
  P --> D["DependencyResolver<br/>MIMA and Maven Resolver"]
  P --> JM["JdkManager<br/>JdkIndex, Unpacker"]
  D --> AB["AppBuilder<br/>javac → jar"]
  JM --> AB
  AB --> CG["CmdGenerator<br/>the java command line"]
  CG --> EX["ProcessBuilder<br/>inheritIO"]
```

`DirectiveParser` is the only seam JBang sits behind. Everything above it works
on jkite's own `SourceDirectives`, so the mirrored copy of JBang's parser is
an implementation detail rather than this project's API.

The built jar is cached under a directory named after a hash of everything the
build is made of: the bytes of every source and resource the script declares,
the name each resource gets inside the jar, the compile options, the requested
Java version, the manifest entries and jkite's own version. So an unchanged
tool is never compiled twice, and a tool that would build differently is never
served from the jar of the build before it.

The jar that comes out is written down too: its size and digest go into
`<name>.jar.id` next to it, and a jar that no longer matches is built again
instead of being run. Every entry in it carries a fixed timestamp, so the same
inputs produce the same bytes and that check means something.

The sources a script declares are the whole of the build. javac is given a
source path of its own so that it cannot fall back on the directory the run
started in and compile a `.java` it finds there: such a file would go into the
jar without being part of the hash, and the jar would then be reused after it
changed. A class that is not in the script has to be named with `//SOURCES`,
and the compile says so when one is missing.
