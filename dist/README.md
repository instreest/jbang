# JBangLite wrapper

This directory is the [JBangLite](https://github.com/instreest/jbang) wrapper.
It is committed to the project so that anyone who checks the project out can run
its `.java` scripts without installing anything first — not even a JDK.

```bash
jbanglitew/jbanglite src/Hello.java      # macOS, Linux, WSL, Git Bash
jbanglitew\jbanglite.cmd src\Hello.java  rem Windows
```

## What happens on the first run

1. If no usable Java is found (`$JBANG_DIR/currentjdk`, the launcher's own
   bootstrap JDK, `JAVA_HOME`, `java` on the `PATH`; Java 11 or newer), the
   launcher downloads a Temurin JDK into `~/.jbang/cache/jdks/bootstrap` and
   verifies its published SHA-256.
2. `jbanglite.jar` is downloaded from the repository and revision recorded in
   `jbanglite.properties`, verified against the SHA-256 recorded there, and
   cached in `jbanglitew/.jbanglite/` — which `.gitignore` keeps out of the project.
3. The JDK a script asks for with `//JAVA` is installed by `jbanglite.jar` itself.

Nothing is written outside `jbanglitew/.jbanglite` and `~/.jbang` (`JBANG_DIR`).

## Files

| File | |
| --- | --- |
| `jbanglite`, `jbanglite.cmd` | the launchers (POSIX shells and Windows) |
| `jbanglite.properties` | the repository, revision and jar checksum to use |
| `install.sh`, `install.cmd` | install and update this directory |
| `.gitignore` | keeps the downloaded jar out of the project |

## Updating

Re-run the installer; it refreshes the launchers, itself and the pinned
revision, and drops the cached jar so the next run fetches the matching one:

```bash
bash jbanglitew/install.sh          # or: jbanglitew\install.cmd
JBANGLITE_REF=v0.2.0 bash jbanglitew/install.sh   # pin a tag or commit instead
```

Commit the changed files afterwards.
