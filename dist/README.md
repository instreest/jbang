# JBangLite

This directory is [JBangLite](https://github.com/instreest/jbanglite) as
installed into a project: the launcher scripts, the two bootstrap scripts,
`jbanglite.properties` and the installer that put them here. All of it is
committed, so anyone who checks the project out can run its `.java` scripts
without installing anything first — not even a JDK.

On macOS, Linux and WSL (and in Git Bash, which hands over to the Windows
launcher):

```bash
jbanglite/jbanglite src/Hello.java
```

On Windows:

```bat
jbanglite\jbanglite.cmd src\Hello.java
```

## What happens on the first run

1. The launcher looks for `jbanglite.jar` next to it. There is none unless this
   project vendors one, so it runs `jbanglite-bootstrap-jar`, which downloads
   the version `jbanglite.properties` pins, checks it against the SHA-256 there
   and keeps it in `~/.jbanglite/cache/jbanglite/<version>`.
2. It looks for a usable Java (`JAVA_HOME`, `javac` on the `PATH`, its own
   bootstrap JDK; a JDK 11 or newer). If none is found it runs
   `jbanglite-bootstrap-jdk`, which downloads a Temurin into
   `~/.jbanglite/cache/jdks/bootstrap` and verifies its published SHA-256.
3. It runs the jar with that Java.
4. The JDK a script asks for with `//JAVA` is installed by `jbanglite.jar`
   itself.

Nothing is written into the project; everything JBangLite downloads goes to
`~/.jbanglite` (`JBANGLITE_DIR`). Both caches are per machine, so other projects on
this machine that pin the same version download nothing at all.

Several runs at once are fine: each download is taken by one run while the
others wait, and every download goes to a file of its own that is renamed into
place, so a parallel build never fails over a half-written file.

## Files

| File | |
| --- | --- |
| `jbanglite`, `jbanglite.cmd` | the launchers (POSIX shells and Windows) |
| `jbanglite.properties` | which JBangLite this project runs: version, URL and SHA-256 |
| `jbanglite-bootstrap-jar`, `.cmd` | download and verify `jbanglite.jar` |
| `jbanglite-bootstrap-jdk`, `.cmd` | download and verify a JDK when the machine has none |
| `install.sh`, `install.cmd` | install and update this directory |
| `LICENSE` | MIT, from JBang |
| `README.md` | this file |

`jbanglite.jar` is deliberately not here: it is a release asset, so this
project's history carries about 46 kB of scripts rather than a 2 MB binary per
update. To pin it into the project anyway, put a `jbanglite.jar` in this
directory; the launcher prefers it and downloads nothing.

In the JBangLite repository the same files live in `dist/`; the installer
copies that directory as it is.

## Which version am I on

```bash
jbanglite/jbanglite --version
```

It prints the version this project pins and the jar that is actually installed,
and downloads nothing.

## Updating

Which JBangLite this project runs is committed here, so the usual way to get a
newer one is `git pull`: the project's author updates it and commits, exactly
as with a Gradle or Maven wrapper.

To update this directory yourself:

```bash
jbanglite/jbanglite --update            # the newest release
jbanglite/jbanglite --update v0.3.0     # or a tag, branch or commit
```

`jbanglite\jbanglite.cmd --update` does the same on Windows. It replaces every
file here, `jbanglite.properties` included, and needs neither the jar nor a JDK.
Commit the changed files afterwards, and remember that this makes the project
run a JBangLite its author has not tried.

`JBANGLITE_DIST_URL` points the jar download at a mirror for one run, for a
machine that cannot reach GitHub releases.
