# JBang をライブラリとして使い、ダウンロードは自前で持つ案

目的は jar サイズではなく **保守性**と、**JBang への依存を明示的な
インターフェース境界にする**こと。自前実装は、明確な利点がある外部ライブラリが
あれば置き換える。

## 1. 結論（先に）

- **技術的には可能**。パーサ（`Directives`）が JBang 側に要求するものは
  13 個の純粋なヘルパだけで、ネットワークには一切触れない。
- **ただし成果物の公開が止まっている**。Maven Central の `dev.jbang:jbang-cli` は
  **0.132.1（2025-10-04）で停止**、一方で配布物 `dev.jbang:jbang.bin` は
  **0.141.0（2026-07）**まで出ている。ライブラリとして依存すると、
  今のミラー（`upstream/main` に追従できる）より**古い所で固定される**。
  → パーサをライブラリ化するのは、この公開が再開されるまで**保留が妥当**。
- **一方で `dev.jbang:devkitman`（0.4.12, 活発）は即戦力**。JBang が JDK 管理を
  切り出した独立ライブラリで、`JdkProvider` / `JdkInstaller` /
  `RemoteAccessProvider` という SPI を持つ。
  `RemoteAccessProvider.downloadFromUrl(String)` は単一メソッドで、
  **「自動ダウンロード前の確認メッセージ」を差し込む理想的な継ぎ目**。
  自前の `jdk/`（5 ファイル 1039 行）を置き換えられる。

## 2. パーサをライブラリとして使う場合の実際の結合度

`Directives.java` が JBang 側に要求するのは次だけ（ミラー元を機械的に抽出）:

```
Util.explode / isPattern / basePathWithoutPattern / isValidPath
Util.isValidClassIdentifier / isValidModuleIdentifier / stringLines / warnMsg
JavaUtil.RequestedVersionComparator
DependencyUtil.looksLikeAGav / looksLikeAPossibleGav
JitPackUtil.possibleMatch
MavenCoordinate.DEFAULT
```

いずれも文字列・パス操作で、**ダウンロードもカタログも trust も通らない**。
上流の `Util` は静的可変状態（verbose/quiet/offline/fresh/cwd）を持つだけで、
重い static initializer はない（`javap` で確認済み）。
したがって「パースだけ上流、ダウンロードは自前」は設計として無理がない。
現在 3 ファイル 826 行のシムを書いているのは、まさにこの 13 個を満たすためで、
依存に置き換えられればそのシム運用が消える。

問題は結合度ではなく**供給**:

| | 現状（ミラー） | ライブラリ依存 |
| --- | --- | --- |
| 追従先 | `upstream/main` の任意の commit | Central に publish された版のみ |
| 現時点の最新 | 上流 main | 0.132.1（9 リリース遅れ） |
| API 安定性 | 差分を自分で見る | `dev.jbang.source.parser` は内部 API、無告知で変わる |
| 手間 | `sync-upstream.sh` + ビルド | バージョン番号 1 行 |

`jbang-cli` の pom は picocli / qute / jsoup / gson / maven-model / MIMA など
17 依存を引く。パース経路が使うのは jspecify だけなので `exclude` で絞れるが、
「使っていない依存が推移的に入る」状態を許容するか、の判断は要る。

## 3. 推奨アーキテクチャ（依存境界をインターフェースにする）

JBang 由来の型を**アプリ内部に漏らさない**のが要点。

```
io.github.instreest.jkite.spi
  ScriptSpec          // パース結果の自前 DTO（deps, repos, sources, files,
                      // javaVersion, mainClass, module, options ...）
  ScriptParser        // ScriptSpec parse(Path, Map<String,String> props)
  DownloadGate        // boolean allow(DownloadRequest)  ← 確認メッセージはここだけ
  JdkService          // Path resolve(RequestedVersion)
  DependencyService   // List<Path> resolve(ScriptSpec)
```

- `ScriptParser` の実装を 2 つ持てるようにする
  - `MirroredDirectivesParser`（今のミラー。既定）
  - `JBangLibraryParser`（`dev.jbang:jbang-cli` 依存。publish が再開されたら）
  同一の**適合テスト（conformance test）**を両実装に流す。
  これで「ライブラリ化」は後から差し替え可能な決定になり、今決めなくてよい。
- ダウンロードは全部 `DownloadGate` の後ろに置く。今ある継ぎ目は 2 つで、
  どちらも既に自前なので実装位置は明確:
  - Maven: `JdkHttpTransporterFactory`（自前 transport）
  - JDK: `jdk/Downloader`（devkitman にするなら `RemoteAccessProvider`）
  ここに集約すれば、確認メッセージ・オフライン・ミラー・プロキシが一箇所になる。

## 4. 自前実装の棚卸し（外部ライブラリへの転換可否）

| 自前 | 行数 | 転換候補 | 判断 |
| --- | --- | --- | --- |
| `jdk/`（Downloader, Jdk, JdkIndex, JdkManager, Unpacker） | 1039 | **`dev.jbang:devkitman` 0.4.12** | **見送り**（既存の jkite スクリプトとの整合性を優先）。以下は参考: **○ 推奨**。JBang 本体も使う。SPI が確認プロンプトの継ぎ目になる。要確認: 既定は Foojay で、現行の Coursier JVM index とは経路が違う（`MetadataJdkInstaller` で寄せられるか要検証）。`JBangJdkProvider` があるのでキャッシュ配置の互換は取りやすい |
| `JdkHttpTransporterFactory` | 259 | `maven-resolver-transport-jdk` | **△ 保留**。2.x 系にしか無く、今の MIMA 2.4.x は resolver 1.9.x。MIMA 3.0（現在 alpha）が安定したら自前 259 行は丸ごと消せる。**追跡対象** |
| `util/Json.java` | 206 | gson | **○**。devkitman を入れると gson は推移的に入るので、追加コストが実質ゼロになる |
| `util/OsDetector.java` | 121 | devkitman `OsUtils` / os-maven-plugin 系 | △。`${os.detected.*}` の互換が要件なので、置換より現状維持が安い |
| `source/parser`（ミラー） | 643 | `dev.jbang:jbang-cli` | **△ 保留**（2 節） |
| `Util`/`JavaUtil`/`DependencyUtil`（シム） | 826 | 同上 | パーサをライブラリ化できた時に消える |
| `MainClassFinder`, `ModuleUtil`, `AppBuilder`, `CmdGenerator` | — | なし | 自前のまま |

## 5. 進め方（依存関係の順）

1. **[実施済]** `spi` パッケージを切り、`DirectiveParser` / `DownloadGate` を導入。
   実装は今のコードのまま、境界だけ入れる（挙動不変、テストで固定）。
2. **[実施済]** `DownloadGate` に確認メッセージを実装（非対話時の既定と
   `JKITE_ASSUME_YES` を含む）。
3. **[見送り]** `jdk/` を devkitman に置き換え。`RemoteAccessProvider` を
   `DownloadGate` 経由にする。Coursier index 要件をここで決着させる。
4. gson 採用で `util/Json.java` を削除。
5. MIMA 3 安定を待って `maven-resolver-transport-jdk` に転換。
6. `jbang-cli` の publish が再開されたら `JBangLibraryParser` を追加し、
   適合テストが通ればミラーとシムを削除。

---

# ④以降の判断（③を見送った前提での再整理）

③（`jdk/` を devkitman に置換）を見送った時点で、④と⑤の前提が両方とも崩れた。
調べ直した結果を先に書く。

## 判断軸

自前か外部かは、サイズではなく次の 4 点で決める。

1. **仕様が外にあるか** — 外部仕様（tar, JSON, JDK 配布形式）を自前で解釈すると、
   追随漏れが例外ではなく**静かな誤り**として出る。
2. **入力を誰が決めるか** — 自分で URL を決めた信頼できる入力か、
   第三者が中身を決められる入力か。
3. **供給リスク** — その依存は今後も出続けるか（公開停止・alpha 放置）。
4. **既存との整合** — キャッシュ配置や jkite スクリプトとの互換を壊さないか。
   ③を見送った理由はこれ。

## ④ `util/Json.java` → gson：**見送り**

前提が崩れた。③を入れないので gson は推移的には入らず、**新規の直接依存**になる
（現在の runtimeClasspath に gson は無い。jar にも `com/google` は 0 件）。

- 入力は Coursier JVM index のみ。URL は自分で決めていて、第三者は中身を決められない（軸 2 が有利）
- 呼び出し箇所は `JdkIndex.java:108` の 1 箇所だけ
- 206 行、`Map`/`List`/`String`/`Double` に落とすだけの読み取り専用

→ **自前のまま**。ただし `Json.java` には現在テストが無いので、
**ユニットテストを足す**（不正 JSON、ネスト、エスケープ、数値）。
gson を入れる理由が生まれるのは「JSON を他の用途でも使い始めたとき」であって、
今ではない。

## ⑤ `JdkHttpTransporterFactory` → `maven-resolver-transport-jdk`：**無期限保留**

前回「MIMA 3 が安定したら」と書いたが、**その MIMA 3 は来ない**。

| | |
| --- | --- |
| MIMA 3.0.0-alpha-3（resolver 2.x, transport-jdk を含む） | **2024-01-19 で停止**、以後 2 年半リリース無し |
| MIMA 2.4.x（現役） | 2.4.48 が 2026-08-07。**resolver 1.9.27** のまま |
| `maven-resolver-transport-jdk` | **2.x 系にしか存在しない** |

取りうる道は 3 つで、いずれも今は割に合わない。

- **(a) 現状維持** — 自前 transport 259 行。テスト（`TestJdkHttpTransporter` 203 行）もある
- **(b) MIMA を捨てて resolver 2.x 直結** — `settings.xml`・ミラー・プロキシ・認証の
  組み立てを自前化することになり、行数は大幅に増える。境界を減らす① の目的に逆行
- **(c) `maven-resolver-transport-apache` に戻す** — 自前 259 行は消えるが、
  Apache HttpClient5 + Gson + public suffix list が戻る。そもそも外した理由に戻るだけ

→ **(a)**。ウォッチ対象は jbang ではなく **MIMA のリリース**で、
再評価の条件は「MIMA の現役ラインが resolver 2.x に載ること」。

## ⑥ パーサのライブラリ化：**条件待ち、条件は明確**

ここだけは将来ほんとうに判断が変わる。判定条件を先に固定しておく。

- **着手条件**: `dev.jbang:jbang-cli` が 0.132.1 より新しい版で publish されること
  （現状 0.132.1 / 2025-10-04 で停止、`jbang.bin` は 0.141.0 / 2026-07 まで進行中）
- **着手コスト**: ① が済んでいるので、`JBangLibraryParser` 1 クラスと、
  `TestDirectiveParser` を継承した適合テストだけ。上のレイヤは触らない
- **再公開されない場合**: 現状維持。①の境界は無駄にならない
  （`Project` から上流型は既に消えている）

## 追加の候補：`jdk/Unpacker.java`（棚卸しの穴）

前回の表から漏れていた。**④〜⑥ の中で一番費用対効果が高いのはこれ**。
228 行の手書き tar/zip 展開で、軸 1（外部仕様）と軸 2（入力）に両方かかる。

現状の実装を読んだ限りの穴:

| | |
| --- | --- |
| pax ヘッダ（`x`/`g`）未対応 | 100 文字超のパスが pax でしか表現されていない配布物で、**切り詰めた名前で静かに書く**（失敗しない） |
| symlink 先を検証していない | エントリのパスは字句チェック済みだが、先に外向きの symlink を作り、後続エントリがそれを**経由して**書く古典的エスケープが残る |
| hardlink（`1`）を symlink として作る | 実害は小さいが忠実ではない |

脅威モデルは弱い（URL は pin した index 由来、SHA-256 検証あり）。
ただし `JdkManager.verifyChecksum` は**公開チェックサムが取れない場合は警告のみで続行**
するので、その経路だけは生の信頼になる。

③を見送った理由（jkite スクリプトとの整合性）は JDK の**取得**の話で、
**展開**は内部実装なのでこの制約は掛からない。よって選択肢は 2 つ:

- **(A) `commons-compress` を入れる** — 追加依存は 1 つだけ（それ自体は無依存）。
  pax・GNU 拡張・リンク・権限を仕様通りに扱う。jbang / devkitman もこれを使っている
- **(B) 自前のまま塞ぐ** — pax ヘッダ対応と symlink 先の検証で +50 行程度、
  悪意ある tar を食わせるテストを追加

依存を増やしたくないなら (B)、「外部仕様の追随は外部に任せる」という方針を
取るなら (A)。どちらでも、**悪意ある tar のテストは要る**。

## 結論

- 今すぐやる価値があるものは ④⑤⑥ には無い
- やるなら **Unpacker**（(A) か (B)、+ テスト）と、**`Json.java` のテスト追加**
- ④は見送り、⑤は MIMA 待ち（無期限）、⑥は jbang-cli の再公開待ち（条件は上記）

---

# 実施記録（④⑤ + Unpacker）

上の判断のうち、サイズを制約から外すという方針転換を受けて次を実施した。

| | 判断 | 結果 |
| --- | --- | --- |
| ⑤ transport | 「無期限保留」から **(c) 純正 HTTP transport に復帰** へ | `JdkHttpTransporterFactory`(259行) と `JKiteRuntime`(52行) と その テスト(203行) を削除。MIMA の `StandaloneStaticRuntime` をそのまま使う |
| Unpacker | **(A) commons-compress** | 手書き tar/zip リーダを置き換え。pax・GNU 拡張・リンク・権限は Commons Compress が扱う |
| ④ Json | **gson に転換** | `util/Json.java`(206行) を削除。gson は transport が連れてくるので追加コストは無い |

## 補足（調査で判明した事実）

- resolver 1.9.x の純正 Apache transport の artifactId は
  `maven-resolver-transport-http`（`-apache` は 2.x 以降の名前）。
  連れてくるのは **HttpClient 4.5.14 / HttpCore 4.4.16 / commons-codec /
  gson / jcl-over-slf4j**。HttpClient **5 ではない**
- HttpClient 4.5.x は 4 系の最終ラインで、Maven 3.9.x が使っているものと同じ。
  枯れているが新機能は来ない。MIMA が resolver 2.x に載れば HttpClient 5 系
  （`maven-resolver-transport-apache`）か `maven-resolver-transport-jdk` に移れる
- commons-compress 1.28 は commons-io / commons-lang3 / commons-codec を引く。
  tar+gzip+zip しか使わないので exclude できる可能性はあるが、
  未テスト経路で `NoClassDefFoundError` になるリスクがあるので入れたままにした
- jar は **2.26 MB → 6.46 MB**。内訳（圧縮後）は commons 系 2.3 MB、
  HttpClient/Core 0.85 MB、resolver/maven 0.55 MB、gson 0.24 MB、
  JKite 自身 0.14 MB

## セキュリティ上の効果

前回指摘した Unpacker の 3 つの穴のうち:

- **pax ヘッダ未対応**（長いパスを静かに切り詰める）→ 解消。テストあり
- **symlink 先の未検証** → リンク先が出力ディレクトリの外を指す場合は拒否、
  さらに書き込み時に親ディレクトリの実パスを検証して、
  リンクを経由した書き込みも拒否する。両方テストあり
- **hardlink を symlink として作る** → 挙動は同じだが、
  リンク先の検証が掛かるようになった

---

# ⑥ の着手条件の訂正：artifact は `jbang.bin` で、公開は続いている

`dev.jbang:jbang-cli` が 0.132.1 で止まっていると書いたのは、**artifact が
改名されただけ**だった。後継は `dev.jbang:jbang.bin` で、pom の `<name>` は
今も "JBang CLI"。公開は止まっていない。

| | |
| --- | --- |
| 最新 | **0.141.0（2026-07-13）**、以降も 0.135.1(2025-12) → 0.136.0 → 0.137.0 → 0.138.0 → 0.140.1 → 0.141.0 と継続 |
| 成果物 | `jbang.bin-0.141.0.jar`（クラスのみ、1.1 MB）、`-all.jar`（全部入り 14.9 MB）、`-sources.jar`、`-javadoc.jar`、`.asc` 署名 |

つまり**ライブラリとして依存できる条件は既に満たされている**。

## 互換性の確認（0.141.0 の jar を実際に読んだ結果）

- `Directives` の public API は**このフォークのミラーと完全一致**（25 メンバ）。
  `Directives.Extended(String, Function<String,String>)` も public で、
  `MirroredDirectiveParser` が呼んでいるコンストラクタそのもの
- パーサが必要とする 13 メンバはすべて存在し、シグネチャも一致
  （`Util.explode` / `isPattern` / `basePathWithoutPattern` / `isValidPath` /
  `isValidClassIdentifier` / `isValidModuleIdentifier` / `stringLines` /
  `warnMsg`、`JavaUtil.RequestedVersionComparator` /
  `checkRequestedVersion`、`DependencyUtil.looksLikeAGav` /
  `looksLikeAPossibleGav`、`JitPackUtil.possibleMatch`、
  `MavenCoordinate.DEFAULT_VERSION`）

`JBangLibraryParser` は `MirroredDirectiveParser` とほぼ同じ中身で書けて、
`TestDirectiveParser` を継承した適合テストで検証できる。①の境界は
そのまま使える。

## 新しい論点：CLI 一式の依存が付いてくる

条件は満たされたが、代わりに別の判断が要る。`jbang.bin` の pom が並べる
ランタイム依存は CLI 全体のもので、パースには要らないものが大半:

```
devkitman, commons-text, commons-compress, aesh(+readline), qute-core,
plexus-java, gson, jsoup, java-properties, slf4j-nop, jcl-over-slf4j,
jandex, mima(context, standalone-static), domtrip-core, domtrip-maven,
tamboui-toolkit, tamboui-aesh-backend, os-source, ...
```

パース経路が実際に使うのは jspecify だけ。aesh（readline）や tamboui（TUI）や
jsoup（HTML）まで `jkite.jar` に入ることになる。`-all.jar` が 14.9 MB
であることが、その規模を端的に示している。

**「exclude はしない」という方針と正面からぶつかる。** 取りうる道は 3 つ:

| | |
| --- | --- |
| (a) 依存ごと丸ごと入れる | 方針には忠実。jar は 6.5 MB から十数 MB へ。使わない TUI/HTML ライブラリを同梱することになる |
| (b) exclude で絞る | jar は小さいままだが「手で刈り込んだ依存ツリー」になり、方針に反する。パーサが将来 Util の別メソッドを呼び始めたら `NoClassDefFoundError` で落ちる |
| (c) ミラーを続ける | 今のまま。ミラーは 9 ファイル、`sync-upstream.sh` で追従。①の境界のおかげで `Project` は既に上流型から切り離されている |

判断材料として付け加えると、⑤（transport）を「jbang が変えたら再評価」と
決めたのと同じ理屈がここにも効く。**ミラーの維持コストは実測で低い**
（全ファイル丸ごとコピーで、上流が無関係な数百ファイルを触っても差分ゼロ）。
一方 (a) のコストは恒久的に効き続ける。

## 決定：(c) ミラー継続

**⑥ は「公開待ち」ではなく「(c) を選んだ」に変わった。** 条件は満たされて
いるが、CLI 一式の依存を抱えるコストの方が、ファイルを丸ごと同期する
コストより大きい、という判断。

再検討の引き金は**公開状況ではなく、同期が実際に高くつき始めたとき**:

- `Directives` が新しい依存を引くようになった
- シムの手当て（`Util` / `JavaUtil` / `DependencyUtil`）が同期のたびに発生する
- `TestDirectiveParser` が通らない差分が上流で入った

これらが起きるまで、`jbang.bin` の新リリースを追う必要はない。
着手する場合の道筋（`JBangLibraryParser` + 適合テスト）は上に書いたとおりで、
①の境界があるので上のレイヤは触らない。

この決定はコード側にも書いてある（`DirectiveParser` の javadoc、
`misc/upstream-mirror.txt`、README の "Staying in sync with JBang"）ので、
この文書を読まなくても気づける。

# ⑤ transport の再評価条件（更新）

「MIMA が resolver 2.x に載ったら」ではなく、**「jbang 本体が transport を
変えたら」**を条件とする。定期的なウォッチは不要。
