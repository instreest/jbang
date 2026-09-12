# Third-party notices

JBangLite is a reduced fork of [JBang](https://github.com/jbangdev/jbang),
Copyright (c) 2020 Max Rydahl Andersen, distributed under the MIT License
(see [LICENSE](LICENSE)). The original copyright and permission notice is kept
unchanged; the modifications made for JBangLite are contributed under the same
MIT License.

JBangLite is not affiliated with, endorsed by, or supported by the JBang
project. The name says where the code comes from, nothing more.

## Code derived from other projects

| Files | Origin | License |
| --- | --- | --- |
| the files listed in `misc/upstream-mirror.txt` (among them `Directives.java`, `MavenCoordinate.java`, `JitPackUtil.java` and their test) | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen, copied unchanged | MIT |
| the files listed in `misc/upstream-shims.txt` and the rest of `src/main/java/dev/jbang/*` and `src/main/scripts/*` | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen, derived | MIT |
| `Jdk.java`, `JdkManager.java`, `Unpacker.java`, parts of `Util.java` (OS/architecture detection, link handling) | [jbangdev/jbang-devkitman](https://github.com/jbangdev/jbang-devkitman), Copyright (c) Max Rydahl Andersen and contributors | MIT |
| `OsDetector.java` (OS and architecture normalisation tables) | [os-maven-plugin](https://github.com/trustin/os-maven-plugin) by Trustin Lee, as also used by the Nisse os-detector in JBang | Apache License 2.0 |

`Placeholders.java` expands the same `${...}` syntax as the
`PropertiesValueResolver` JBang carries, but is JBangLite's own code: that file
reached JBang from the JBoss projects with a licence history we could not
establish, so it was reimplemented rather than mirrored.

## Data downloaded at runtime

The list of downloadable JDKs comes from the JVM index published by the
[Coursier](https://github.com/coursier/jvm-index) project (Apache License 2.0)
as `io.get-coursier.jvm.indices:index-<platform>` on Maven Central. Only the
index is retrieved from there; the JDK archives themselves are downloaded from
each distributor's own site, and the license of the JDK that gets installed is
the one of that distribution (Eclipse Temurin, the default, is GPLv2 with the
Classpath Exception).

## Libraries bundled in `jbanglite.jar`

| Library | License |
| --- | --- |
| [MIMA](https://github.com/maveniverse/mima) (`eu.maveniverse.maven.mima:*`) | Eclipse Public License 2.0 |
| [Apache Maven Resolver](https://maven.apache.org/resolver/) without its HTTP transport, and Apache Maven model/settings builders (`org.apache.maven.resolver:*`, `org.apache.maven:*`) with their Plexus dependencies | Apache License 2.0 |
| [ASM](https://asm.ow2.io/) (`org.ow2.asm:asm`, needed by the Maven model builder) | BSD 3-Clause |
| [SLF4J](https://www.slf4j.org/) (`slf4j-api`, `slf4j-nop`) | MIT License |

HTTP downloads go through `java.net.http.HttpClient` in the JDK
(`JdkHttpTransporterFactory`), so Apache HttpClient, Gson and the public
suffix list are not bundled. The header checksum extraction in that class
(`x-checksum-*`, `x-goog-meta-checksum-*`, the Nexus 2 `ETag`) follows the
transport's `XChecksumChecksumExtractor` and `Nexus2ChecksumExtractor`.

MIMA is distributed under the Eclipse Public License 2.0, which asks that
recipients be told where to get the source: it is at
<https://github.com/maveniverse/mima>, and every released version is on Maven
Central with its `-sources` jar.

### Notices inside the jar

`jbanglite.jar` carries, under `META-INF/notices/<group>-<artifact>-<version>/`,
the `LICENSE` and `NOTICE` files each bundled artifact ships, one directory per
artifact so that none overwrites another. Artifacts that ship no such file of
their own have their text taken from `misc/notices/` in this repository; ASM,
whose BSD 3-Clause licence asks for the notice to travel with a binary
distribution, is there. `META-INF/LICENSE-jbanglite.txt` is JBangLite's own
licence and `META-INF/THIRD-PARTY.md` is this file.

The exact list of bundled artifacts can be printed with
`./gradlew dependencies --configuration runtimeClasspath`.
