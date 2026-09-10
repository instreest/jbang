# Third-party notices

JBangLite is a reduced fork of [JBang](https://github.com/jbangdev/jbang),
Copyright (c) 2020 Max Rydahl Andersen, distributed under the MIT License
(see [LICENSE](LICENSE)). The original copyright and permission notice is kept
unchanged; the modifications made for JBangLite are contributed under the same
MIT License.

## Code derived from other projects

| Files | Origin | License |
| --- | --- | --- |
| the files listed in `misc/upstream-mirror.txt` (among them `Directives.java`, `MavenCoordinate.java`, `JitPackUtil.java` and their test) | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen, copied unchanged | MIT |
| the files listed in `misc/upstream-shims.txt` and the rest of `src/main/java/dev/jbang/*` and `src/main/scripts/*` | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen, derived | MIT |
| `Jdk.java`, `JdkManager.java`, `Unpacker.java`, parts of `Util.java` (OS/architecture detection, link handling) | [jbangdev/jbang-devkitman](https://github.com/jbangdev/jbang-devkitman), Copyright (c) Max Rydahl Andersen and contributors | MIT |
| `PropertiesValueResolver.java` | Written by David M. Lloyd (Red Hat) for the JBoss/WildFly projects, included in JBang | Apache License 2.0 / MIT (as distributed in JBang) |
| `OsDetector.java` (OS and architecture normalisation tables) | [os-maven-plugin](https://github.com/trustin/os-maven-plugin) by Trustin Lee, as also used by the Nisse os-detector in JBang | Apache License 2.0 |

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

The exact list of bundled artifacts can be printed with
`./gradlew dependencies --configuration runtimeClasspath`.
