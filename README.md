# Room Seed

在 Android 构建时将 Room 导出的 schema 和 seed 文件生成 SQLite 数据库，并打包到对应 Variant 的 APK assets。适合给词典、目录和其他离线功能提供初始数据。

## 快速上手

### 1. 配置插件和 Room schema 导出

在项目的 `settings.gradle.kts` 中，将 `mavenCentral()` 加入现有的插件仓库配置。

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

在 Android 应用模块和每个提供 seed 的 Android 库模块应用插件。在现有的 `plugins` 块中添加以下配置。

```kotlin
plugins {
    id("top.cyclops.room-seed") version "0.1.0"
}
```

在声明 Room 数据库的模块启用 KSP，添加对应的 Room compiler 依赖，并应用官方的 `androidx.room` 或 `androidx.room3` 插件。数据库需启用 `@Database(exportSchema = true)`。Room 2 在模块的 `build.gradle.kts` 中配置：

```kotlin
room {
    schemaDirectory("$projectDir/schemas")
}
```

Room 3 使用同样的配置，块名为 `room3`。发布版的自动接线在本仓库使用的 AGP 9.4.1、Room 2.8.5 和 Room 3.0.3 上验证过。应用从 Android 库消费数据库时，schema 导出配置放在声明数据库的库模块。

### 2. 添加清单和数据

假设已有 `com.example.books.LibraryDatabase`，其中 `books` 表有自动生成的主键，以及 `title`、`author` 两列。在提供 seed 的模块下创建：

```text
src/main/seed/library/
├── seek.yaml
└── books.json
```

`seek.yaml`：

```yaml
database: com.example.books.LibraryDatabase
databaseFilePath: databases/library.db
files:
  - books.json
```

`books.json`：

```json
[
  {"title": "The War of the Worlds", "author": "H. G. Wells"}
]
```

文件名 `books.json` 对应表名 `books`，对象键对应数据库列名。JSON 顶层必须是对象数组。也可以导入 JSONL、CSV 和 SQL。

### 3. 构建 APK 并打开数据库

```sh
./gradlew :app:assembleDebug
```

构建会将数据库放到 APK 的 `assets/databases/library.db`。启动应用时，将清单里的 `databaseFilePath` 传给 `createFromAsset`。Room 2 示例：

```kotlin
val database = Room.databaseBuilder(
    context.applicationContext,
    LibraryDatabase::class.java,
    "library.db",
)
    .createFromAsset("databases/library.db")
    .build()
```

Room 3 示例：

```kotlin
val database = Room.databaseBuilder<LibraryDatabase>(
    context.applicationContext,
    "library.db",
)
    .setDriver(BundledSQLiteDriver())
    .createFromAsset("databases/library.db")
    .build()
```

Room 3 示例使用 `androidx.room3.Room` 和 `androidx.sqlite.driver.bundled.BundledSQLiteDriver`，并依赖 `androidx.sqlite:sqlite-bundled`。`createFromAsset` 在需要创建预置数据库时使用该 asset。验证数据改动时，可在测试设备上用全新安装检查首次打开的结果。

## 自动发现

应用消费当前 Variant 直接依赖的同一构建内库发布的 seed 产物，沿用 AGP 的 Variant 选择，包括 `matchingFallbacks`。

插件递归查找 `seek.yaml`。含有种子文件的目录必须由一份清单管理。合用版读取词典、书籍模块以及自己的清单。

变体数据放在对应 Source Set 下，例如 `src/debug/seed/`、`src/china/seed/` 或 `src/chinaRelease/seed/`。同一个数据库采用优先级最高的目录中的清单。优先级从高到低为完整 Variant、build type、组合 flavor、各 flavor（按维度声明顺序）、`src/main/seed/`、旧版模块根目录下的 `seed/`。库模块按 AGP 实际选中的库 Variant 应用同样的规则。

可通过 `seedDir` 更改共用目录。

```kotlin
roomSeed {
    seedDir.set("data/offline")
}
```

路径以该模块为基准。设置后，该目录代替两个默认共用目录，其他 Source Set 仍按相同优先级查找。一个模块的数据库按 Variant 汇总到 `build/intermediates/room-seed/<variant>/`，目录内保留各清单的 `databaseFilePath`。执行 `./gradlew :demo-library:assembleDebug` 或 `./gradlew :demo-library:publishDebugRoomSeed` 可生成 `demo-library/build/intermediates/room-seed/debug/databases/library.db`。应用打包会生成依赖的 seed 并打包最终数据库。单独执行 KSP 只运行编译器和 Room 的 Schema 同步流程。

### 清单字段

清单可放在扫描根目录或子目录。嵌套清单管理自己的子树。

```yaml
database: com.example.books.LibraryDatabase
databaseFilePath: databases/library.db
files:
  - '**/*.json'
```

- `database` 是 Room 导出的数据库类名。
- `databaseFilePath` 是 APK 的 assets 内的相对 `.db` 路径，必须手动填写。同一个 Variant 的输出发生冲突时，构建会列出两份清单。
- `files` 按列表顺序导入相对路径或通配符。支持 UTF-8 的 `.sql`、`.csv`、`.json` 和 `.jsonl`。单个 `*` 匹配一层中的文件名，独立的 `**` 匹配零层或多层目录。每条通配符的匹配结果按相对路径排序。以通配符开头的条目请加引号。无匹配、重复匹配或越出目录的路径会使构建失败。

应用清单匹配到依赖模块中的同一个 `database` 时，插件复制库模块已生成的数据库，再用应用清单的 `files` 追加数据。追加步骤跳过建表，原有 FTS 同步触发器会更新索引。APK 只打包该数据库的最终版本，路径以应用清单的 `databaseFilePath` 为准。合用版的 `src/main/seed/library/seek.yaml` 追加了 *The War of the Worlds*，可以搜索 `war of the worlds`。

## 数据格式与构建行为

CSV、JSON 和 JSONL 的文件名对应 Room 表名，CSV 表头和对象键对应实际列名。CSV 每条记录与表头列数一致，JSONL 每行一个对象。`.json` 的顶层必须是对象数组，允许空数组，按数组顺序导入。CSV 的 `\N` 和 JSON、JSONL 的 `null` 表示 SQL NULL，CSV 的 `\\N` 表示字面文本 `\N`。省略 CSV 表头中的列或对象键时，由 SQLite 应用列默认值，缺少无默认值的必填列会报错，自动生成的主键可以省略。值按 Room 存储类型校验，BLOB 使用 Base64。

生成任务选择数据库目录中编号最大的 JSON，校验版本和 `room_master_table` 初始化声明，并解析表、列、主键、索引、外键和视图。Room 3 的 `ROWID`、`AUTOINCREMENT` 和 `WITHOUT ROWID` 可用于预置库。虚拟表支持 Room 2 的 FTS4 和 Room 3 的 FTS5。

FTS4 或 FTS5 使用外部内容表时，将 seed 写到 `contentEntity` 对应的表。新建的 FTS5 库仅导入 CSV、JSON 或 JSONL 时，导入完成后批量重建全文索引并恢复 Room 同步触发器。追加数据及含 SQL 的导入通过触发器同步索引。导入后会检查全文索引与内容表是否一致。独立 FTS4 和 FTS5 表可直接导入数据。Android 上的 FTS5 示例使用 `BundledSQLiteDriver`。

任务在同一事务中创建表、索引和视图，导入预置数据，再写入 Room 元表和 `user_version`。通过结构、外键和完整性检查后，原子发布到 AGP 分配的生成目录。任一导入失败会回滚并保留上一次成功产物。

SQL 脚本使用 UTF-8，支持 BOM、注释和多条语句，包括 `INSERT … SELECT`。请仅导入可信脚本。脚本可以使用临时表和触发器，须保留插件管理的事务及导入前的持久 schema。插件在提交前复验数据库结构。

只导入 CSV、JSON 或 JSONL 的生成任务，在 schema、清单指定的输入、基础数据库及输出均未变化时会显示为 `UP-TO-DATE`。包含 SQL 脚本的任务每次执行，以支持时间函数等依赖运行环境的逻辑。

Gradle 插件使用 AndroidX `BundledSQLiteDriver` 及其预编译的 JVM 原生库，无需在本机下载 SQLite 源码或安装 C 编译器。该依赖提供 macOS arm64、Linux arm64/x64 和 Windows x64 原生库。SQL 语法和功能以构建时的 Bundled SQLite 为准，Android 系统 SQLite 的版本和功能可能随设备而不同。

## 工程

| 路径 | 用途 |
| --- | --- |
| `seed-gradle-plugin/` | Kotlin 编写的 JVM Gradle 插件。插件 ID 为 `top.cyclops.room-seed`，源码包为 `top.cyclops.roomseed.gradle`。 |
| `demo/` | 同一个 APK 同时运行 Room 2 和 Room 3，分别提供词典 FTS4 和书籍 FTS5 搜索。 |
| `demo-dictionary/`、`demo-library/` | 合并示例依赖的 Android 库。各自保存数据库源码、Room Schema 和预置数据，并使用对应版本的官方 Room 插件。 |

根构建通过 `pluginManagement.includeBuild` 直接消费插件源码。依赖版本集中在 `gradle/libs.versions.toml`。开发基线为 Gradle 9.9.0、AGP 9.4.1 和 Android SDK 36。Gradle Daemon 使用 JetBrains JDK 21，插件编译使用 JDK 17。合并示例使用 AGP 内置 Kotlin，最低 API 为 30。

## 运行示例

安装 JDK 17 和 Android SDK 36。通过 Android Studio 配置 SDK，或设置 `ANDROID_HOME`。以下命令可验证插件、构建示例并安装合用版。

```sh
./gradlew :seed-gradle-plugin:validatePlugins
./gradlew :seed-gradle-plugin:test
./gradlew :demo:assembleDebug :demo:assembleRelease
./gradlew :demo:installDebug
```

合并示例同时运行 Room 2 和 Room 3。词典可以搜索 `atlas` 或 `made from a feather`，书籍可以搜索 `Austen` 或 `war of the worlds`。清空搜索框并提交会恢复完整列表。

## 待协定

- SQLite 版本升级策略及宿主平台发布范围。
- 显式接线契约和完整 DSL 定稿，以及何时开放固定版本的官方 Room 自动适配。

## 许可证

[MIT](LICENSE)


## 0.1.1：FTS4 ICU 本地验证

本快照保留 `BundledSQLiteDriver 2.7.1`，通过原生 SQLite 扩展注册 FTS4 `icu`。
原样执行 Room 导出的建表 SQL，不改写 tokenizer、参数或 identity hash。
FTS4 的 simple、porter、unicode61、icu 可在同一数据库混用；FTS5 使用 SQLite
内置 tokenizer，不提供自定义 FTS5 ICU。Room 本身不提供 ICU 二进制实现。

当前 ICU 原生包仅支持 **macOS arm64（Apple Silicon）**，静态包含 ICU 78.3
及完整数据，不依赖消费机器安装 Homebrew。其他平台仍可使用原有 tokenizer，
ICU 能力检测会明确报错。本地快照不是多平台正式发行版。
原生库约 33 MiB；它是构建依赖，不会自动打入 Android APK。

在消费项目 `settings.gradle.kts` 的 `pluginManagement.repositories` 中添加
`mavenLocal()`，然后使用：

```kotlin
plugins {
    id("top.cyclops.room-seed") version "0.1.1"
}
```

本仓库 demo 使用 included build；本地发布命令为
`./gradlew -p seed-gradle-plugin test publishToMavenLocal`。

### 独立 FTS 表与自定义同步

没有 `contentEntity` 的 FTS 表不自动同步业务表。默认 SQL 应显式写入 FTS 表，
例如 `INSERT INTO question_fts(rowid, body) SELECT id, body FROM question`，
字段映射由消费项目提供，且避免同时触发同步造成重复写入。
`INSERT INTO question_fts(question_fts) VALUES ('rebuild')` 仅从 FTS 表自身内容重建，
不会从业务表回填。

如果导入过程依赖自定义触发器，可在同一 seed SQL 中先创建触发器、再插入业务数据，
最后删除这些导入专用触发器。触发器在创建之后的语句中生效；此前已有数据仍需显式回填。
当前严格校验仍拒绝最终数据库中未在 Room schema 声明的额外触发器；
本快照没有放宽校验或实现持久自定义触发器配置。应用运行时触发器由消费项目负责。

### 原生实现与维护

`seed-gradle-plugin/src/main/native` 保留 SQLite 3.50.4 的官方 FTS3/4 ICU
实现及 tokenizer 接口，增加扩展注册入口；注册后恢复原 tokenizer 注册开关。
SQLite 代码采用原文件中的公有领域声明，ICU 许可证随 jar 放在 `native/ICU-LICENSE.txt`。
macOS arm64 可用 `sh seed-gradle-plugin/src/main/native/build-macos-arm64.sh` 重建；
需要 clang、SQLite SDK 头文件及 ICU 78.3 静态库，`ICU_PREFIX` 可指定安装目录。
正式跨平台发行前仍需各平台构建、最低系统版本及原生加载验证。


### 多平台开发状态

加载器现支持 `macos-aarch64`、`macos-x86_64`、`linux-aarch64`、
`linux-x86_64`、`windows-x86_64` 五个平台路径，识别 JVM 的 amd64/x86_64
和 arm64/aarch64 别名。Linux 目标是 glibc，musl 不在支持范围内。

统一构建入口：`bash seed-gradle-plugin/src/main/native/build-host.sh`。
默认从固定提交构建 ICU 78.3 静态库及完整数据；已有相同版本静态库时可设置
`ICU_PREFIX`，SQLite 头文件目录可由 `SQLITE_INCLUDE` 指定。
Windows 使用 MSYS2 MINGW64，构建脚本静态链接 GCC/C++ 运行库。

`.github/workflows/native-icu.yml` 在五种宿主上分别构建和执行完整 JVM 测试，
全部通过后汇总为 `room-seed-multiplatform` artifact。
运行 `./gradlew -p seed-gradle-plugin jar -PrequireAllNativePlatforms=true`
会检查五种原生库齐全，否则失败。此任务不能代替各平台执行测试。
当前本地仅验证 macOS arm64；其他平台和最低系统版本仍待 CI 实测。

当前分发仍将现有原生资源打入一个 jar；尚未实现按宿主自动解析独立 Maven 原生包。
因此本地 snapshot 不会声称已携带未构建的平台库，多平台 CI 汇总包会包含全部五种库。


### 标签自动发布

推送 `X.Y.Z` 标签会触发 Native ICU 工作流；五个平台全部编译测试成功、
汇总包测试成功后，自动签名并发布到 Maven Central。发布版本取标签名。
手动运行和 PR 只构建测试，不发布。已有 Central 版本不能覆盖，请使用新版本号。
发布密钥只在最后的发布步骤可用，不传给各平台编译任务。
需要仓库 Secrets：MAVEN_CENTRAL_USERNAME、MAVEN_CENTRAL_PASSWORD、
SIGNING_KEY_ID、SIGNING_PASSWORD、SIGNING_KEYRING_BASE64。
