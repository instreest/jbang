# JBangLite

This directory lets you run this project's `.java` tools without installing
anything first — not even a JDK.

```bash
jbanglite/jbanglite path/to/Tool.java      # macOS, Linux, WSL
jbanglite\jbanglite.cmd path\to\Tool.java  # Windows
```

Everything here is committed with the project, the way a Gradle or Maven
wrapper is. It is [JBangLite](https://github.com/instreest/jbanglite), a
reduced fork of [JBang](https://github.com/jbangdev/jbang), not affiliated with
the JBang project.

## The first run

The launcher downloads what the machine is missing and checks each download
against a SHA-256 committed in `jbanglite.properties`:

1. `jbanglite.jar`, into `~/.jbanglite/cache/jbanglite/<version>`.
2. A JDK, into `~/.jbanglite/cache/jdks/bootstrap`, but only if the machine has
   no usable one. `JAVA_HOME` and a `javac` on the `PATH` are used when they are
   Java 11 or newer.
3. The JDK a tool asks for with `//JAVA`, and its `//DEPS` from Maven Central.

Nothing is written into the project, and the caches are per machine, so other
projects pinning the same version download nothing. Several runs at once are
safe: one run downloads while the others wait.

## Files

| File | |
| --- | --- |
| `jbanglite`, `jbanglite.cmd` | the launchers |
| `jbanglite.properties` | which JBangLite and which JDK this project uses: version, URL and SHA-256 |
| `jbanglite-bootstrap-jar`, `.cmd` | download and verify `jbanglite.jar` |
| `jbanglite-bootstrap-jdk`, `.cmd` | download and verify a JDK when the machine has none |
| `install.sh`, `install.cmd` | install and update this directory |
| `LICENSE`, `README.md` | |

`jbanglite.jar` is not here on purpose: it is a download, so this project's
history carries about 50 kB of scripts rather than a binary per update. To pin
it into the project anyway, put a `jbanglite.jar` in this directory; the
launcher prefers it.

## Which version is this

```bash
jbanglite/jbanglite --version
```

names the version that will run, and downloads nothing.

## Updating

Normally you do not: the version is committed, so `git pull` brings whatever
this project's maintainer chose. To change it yourself:

```bash
jbanglite/jbanglite --update            # the newest release
jbanglite/jbanglite --update v0.3.0     # or a particular one
```

`jbanglite\jbanglite.cmd --update` does the same on Windows. It replaces every
file here and needs neither the jar nor a JDK. Commit the result, and bear in
mind that it makes this project run a JBangLite its maintainer has not tried.

`JBANGLITE_DIST_URL` points the jar download at a mirror for one run, for a
machine that cannot reach GitHub releases.
