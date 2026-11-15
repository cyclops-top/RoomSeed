# Room Seed

Room Seed 是面向 Android 应用 Variant 的 Gradle 插件。它在 Room 导出并同步 Schema 后，从对应数据库目录选择数字版本最大的 JSON，按声明顺序导入预置数据，并将生成的 SQLite 文件加入该 Variant 的 assets。

## 使用 Maven Central 版本

在项目的 `settings.gradle.kts` 中为插件解析配置仓库。

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

在 Android 应用模块和提供 seed 的 Android 库模块应用插件。

```kotlin
plugins {
    id("top.cyclops.room-seed") version "0.1.0"
}
```

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

## 自动发现

在 Android 应用模块和提供 seed 的 Android 库模块应用 `top.cyclops.room-seed`。共用数据放在各模块的 `src/main/seed/`。应用消费当前 Variant 直接依赖的同一构建内库发布的 seed 产物，沿用 AGP 的 Variant 选择，包括 `matchingFallbacks`。Schema 生产模块继续使用 KSP 和对应的官方 Room 插件，并在 `room` 或 `room3` 中配置 `schemaDirectory`。

插件递归查找 `seek.yaml`。含有种子文件的目录必须由一份清单管理。合用版读取词典、书籍模块以及自己的清单。

变体数据放在对应 Source Set 下，例如 `src/debug/seed/`、`src/china/seed/` 或 `src/chinaRelease/seed/`。同一个数据库采用优先级最高的目录中的清单。优先级从高到低为完整 Variant、build type、组合 flavor、各 flavor（按维度声明顺序）、`src/main/seed/`、旧版模块根目录下的 `seed/`。库模块按 AGP 实际选中的库 Variant 应用同样的规则。

可通过 `seedDir` 更改共用目录。

```kotlin
roomSeed {
    seedDir.set("data/offline")
}
```

路径以该模块为基准。设置后，该目录代替两个默认共用目录，其他 Source Set 仍按相同优先级查找。一个模块的数据库按 Variant 汇总到 `build/intermediates/room-seed/<variant>/`，目录内保留各清单的 `databaseFilePath`。执行 `./gradlew :demo-library:assembleDebug` 或 `./gradlew :demo-library:publishDebugRoomSeed` 可生成 `demo-library/build/intermediates/room-seed/debug/databases/library.db`。应用打包会生成依赖的 seed 并打包最终数据库。单独执行 KSP 只运行编译器和 Room 的 Schema 同步流程。

### 必填的 seek.yaml

清单可放在扫描根目录或子目录。嵌套清单管理自己的子树。

```yaml
database: top.cyclops.roomseed.demo.dictionary.DictionaryDatabase
databaseFilePath: databases/dictionary.db
files:
  - '**/*.jsonl'
```

- `database` 是 Room 导出的数据库类名。
- `databaseFilePath` 是 APK 的 assets 内的相对 `.db` 路径，必须手动填写。同一个 Variant 的输出发生冲突时，构建会列出两份清单。
- `files` 按列表顺序导入相对路径或通配符。支持 UTF-8 的 `.sql`、`.csv`、`.json` 和 `.jsonl`。单个 `*` 匹配一层中的文件名，独立的 `**` 匹配零层或多层目录。每条通配符的匹配结果按相对路径排序。以通配符开头的条目请加引号。无匹配、重复匹配或越出目录的路径会使构建失败。

应用清单匹配到依赖模块中的同一个 `database` 时，插件复制库模块已生成的数据库，再用应用清单的 `files` 追加数据。追加步骤跳过建表，原有 FTS 同步触发器会更新索引。APK 只打包该数据库的最终版本，路径以应用清单的 `databaseFilePath` 为准。合用版的 `src/main/seed/library/seek.yaml` 追加了 *The War of the Worlds*，可以搜索 `war of the worlds`。

生成任务选择数据库目录中编号最大的 JSON，校验版本和 `room_master_table` 初始化声明，并解析表、列、主键、索引、外键和视图。Room 3 的 `ROWID`、`AUTOINCREMENT` 和 `WITHOUT ROWID` 可用于预置库。虚拟表支持 Room 2 的 FTS4 和 Room 3 的 FTS5。

FTS4 或 FTS5 使用外部内容表时，将 seed 写到 `contentEntity` 对应的表。新建的 FTS5 库仅导入 CSV、JSON 或 JSONL 时，导入完成后批量重建全文索引并恢复 Room 同步触发器。追加数据及含 SQL 的导入通过触发器同步索引。导入后会检查全文索引与内容表是否一致。独立 FTS4 和 FTS5 表可直接导入数据。Android 上的 FTS5 示例使用 `BundledSQLiteDriver`。

CSV、JSON 和 JSONL 的文件名对应 Room 表名，CSV 表头和对象键对应实际列名。CSV 每条记录与表头列数一致，JSONL 每行一个对象。`.json` 的顶层必须是对象数组，允许空数组，按数组顺序导入。CSV 的 `\N` 和 JSON、JSONL 的 `null` 表示 SQL NULL，CSV 的 `\\N` 表示字面文本 `\N`。省略 CSV 表头中的列或对象键时，由 SQLite 应用列默认值，缺少无默认值的必填列会报错，自动生成的主键可以省略。值按 Room 存储类型校验，BLOB 使用 Base64。

任务在同一事务中创建表、索引和视图，导入预置数据，再写入 Room 元表和 `user_version`。通过结构、外键和完整性检查后，原子发布到 AGP 分配的生成目录。任一导入失败会回滚并保留上一次成功产物。

SQL 脚本使用 UTF-8，支持 BOM、注释和多条语句，包括 `INSERT … SELECT`。请仅导入可信脚本。脚本可以使用临时表和触发器，须保留插件管理的事务及导入前的持久 schema。插件在提交前复验数据库结构。

只导入 CSV、JSON 或 JSONL 的生成任务，在 schema、清单指定的输入、基础数据库及输出均未变化时会显示为 `UP-TO-DATE`。包含 SQL 脚本的任务每次执行，以支持时间函数等依赖运行环境的逻辑。

Gradle 插件使用 AndroidX `BundledSQLiteDriver` 及其预编译的 JVM 原生库，无需在本机下载 SQLite 源码或安装 C 编译器。该依赖提供 macOS arm64、Linux arm64/x64 和 Windows x64 原生库。SQL 语法和功能以构建时的 Bundled SQLite 为准，Android 系统 SQLite 的版本和功能可能随设备而不同。

合并示例通过 `createFromAsset` 打开 `assets/databases/dictionary.db` 和 `assets/databases/library.db`。词典使用 Room 2 FTS4，可搜索 `atlas` 或 `made from a feather`。书籍使用 Room 3 FTS5，可搜索 `Austen` 或 `war of the worlds`。清空搜索框并提交会恢复完整列表。

## 待协定

- SQLite 版本升级策略及宿主平台发布范围。
- 显式接线契约和完整 DSL 定稿，以及何时开放固定版本的官方 Room 自动适配。
