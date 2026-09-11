# JBangLite

This directory is [JBangLite](https://github.com/instreest/jbang) as installed
into a project: the launchers, `jbanglite.jar` and the installer that put them
here. All of it is committed, so anyone who checks the project out can run its
`.java` scripts without installing anything first — not even a JDK.

On macOS, Linux and WSL (and in Git Bash, which hands over to the Windows
launcher):

```bash
jbanglitew/jbanglite src/Hello.java
```

On Windows:

```bat
jbanglitew\jbanglite.cmd src\Hello.java
```

## What happens on the first run

1. The launcher looks for a usable JDK (its own bootstrap JDK, `JAVA_HOME`,
   `javac` on the `PATH`; a JDK 11 or newer). If none is found it runs
   `jbanglite-bootstrap-jdk`, which downloads a Temurin JDK into
   `~/.jbang/cache/jdks/bootstrap` and verifies its published SHA-256.
2. It runs `jbanglite.jar` from this directory with that Java.
3. The JDK a script asks for with `//JAVA` is installed by `jbanglite.jar` itself.

Nothing is written into the project; everything JBangLite downloads goes to
`~/.jbang` (`JBANG_DIR`).

Several runs at once are fine: the JDK download is taken by one run while the
others wait for it, and every other download goes to a file of its own that is
renamed into place, so a parallel build never fails over a half-written file.

## Files

| File | |
| --- | --- |
| `jbanglite`, `jbanglite.cmd` | the launchers (POSIX shells and Windows): find a JDK, run the jar |
| `jbanglite-bootstrap-jdk`, `jbanglite-bootstrap-jdk.cmd` | download a JDK when the machine has none; run by the launchers, or by hand |
| `jbanglite.jar` | JBangLite itself |
| `install.sh`, `install.cmd` | install and update this directory |
| `LICENSE` | MIT, from JBang |
| `README.md` | this file |

In the JBangLite repository the same files live in `dist/`; the installer
copies that directory as it is.

## Updating

Re-run the installer; it replaces every file here, `jbanglite.jar` included,
with the one from the chosen revision:

```bash
bash jbanglitew/install.sh                        # newest, from main
JBANGLITE_REF=v0.2.0 bash jbanglitew/install.sh   # a tag or commit instead
```

`jbanglitew\install.cmd` does the same on Windows. Commit the changed files
afterwards. `jbanglitew/jbanglite version` prints which version is installed,
as `0.1.0-lite+<commit>`, the commit of the JBangLite repository the jar was
built from.
