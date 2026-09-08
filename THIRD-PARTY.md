# Third-party notices

JBangLite is a reduced fork of [JBang](https://github.com/jbangdev/jbang),
Copyright (c) 2020 Max Rydahl Andersen, distributed under the MIT License
(see [LICENSE](LICENSE)). The original copyright and permission notice is kept
unchanged; the modifications made for JBangLite are contributed under the same
MIT License.

## Code derived from other projects

| Files | Origin | License |
| --- | --- | --- |
| `src/main/java/dev/jbang/*` (Directives, CommandBuffer, DependencyResolver, DependencyCache, MavenCoordinate, ArtifactInfo, Util, Settings, ExitException, Main, launcher scripts `src/main/scripts/*`) | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen | MIT |
| `Jdk.java`, `JdkManager.java`, `Unpacker.java`, parts of `Util.java` (OS/architecture detection, link handling) | [jbangdev/jbang-devkitman](https://github.com/jbangdev/jbang-devkitman), Copyright (c) Max Rydahl Andersen and contributors | MIT |
| `PropertiesValueResolver.java` | Written by David M. Lloyd (Red Hat) for the JBoss/WildFly projects, included in JBang | Apache License 2.0 / MIT (as distributed in JBang) |
| `OsDetector.java` (OS and architecture normalisation tables) | [os-maven-plugin](https://github.com/trustin/os-maven-plugin) by Trustin Lee, as also used by the Nisse os-detector in JBang | Apache License 2.0 |

## Libraries bundled in `jbang.jar`

| Library | License |
| --- | --- |
| [MIMA](https://github.com/maveniverse/mima) (`eu.maveniverse.maven.mima:*`) | Eclipse Public License 2.0 |
| [Apache Maven Resolver](https://maven.apache.org/resolver/) and Apache Maven model/settings builders (`org.apache.maven.resolver:*`, `org.apache.maven:*`) and their Apache dependencies (commons-lang3, plexus-*, sisu) | Apache License 2.0 |
| [SLF4J](https://www.slf4j.org/) (`slf4j-api`, `slf4j-nop`, `jcl-over-slf4j`) | MIT License |
| `javax.inject`, `javax.annotation` | Apache License 2.0 / CDDL |

The exact list of bundled artifacts can be printed with
`./gradlew dependencies --configuration runtimeClasspath`.

## Runtime bundles (`jbang-<version>-<os>-<arch>.tar.gz` / `.zip`)

These archives additionally contain `jbang/runtime/`, a runtime image created with
`jlink` from a JDK 25. By default that JDK is
[Eclipse Temurin](https://adoptium.net/), distributed under the
GNU General Public License, version 2, with the Classpath Exception
(GPLv2+CE); the full license texts shipped with the JDK are preserved in
`jbang/runtime/legal/`. The `legal/` folder must be kept when redistributing a
bundle. If a bundle is built from a different JDK (`-PjlinkJdk` / `-PjlinkJdkUrl`),
the license of that JDK applies to `runtime/` instead; note that the Oracle JDK is
distributed under the Oracle No-Fee Terms and Conditions, which are different
from the GPL, so only redistribute bundles built from an OpenJDK build such as
Temurin unless you have checked those terms.
