# Sources

- fts3_icu.c, fts3_tokenizer.h: https://github.com/sqlite/sqlite/tree/version-3.50.4/ext/fts3
- sqlite3ext.h: https://github.com/sqlite/sqlite/blob/version-3.50.4/src/sqlite3ext.h
- ICU 78.3 static archives and full data: Homebrew icu4c@78 78.3.
- fts3Int.h is a local shim using the extension API rather than SQLite internal headers.
- extension.c registers only the FTS4 ICU tokenizer; no ICU SQL function overrides.

The source files retain their upstream public-domain notices. ICU's complete license is
packaged in ../resources/native/ICU-LICENSE.txt. The snapshot binary is macOS arm64 only.
