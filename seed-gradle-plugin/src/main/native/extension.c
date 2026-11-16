#define SQLITE_ENABLE_ICU 1
#include "fts3_icu.c"
#ifdef _WIN32
__declspec(dllexport)
#endif
int sqlite3_roomseedicu_init(sqlite3 *db, char **error, const sqlite3_api_routines *api) {
  SQLITE_EXTENSION_INIT2(api);
  sqlite3_stmt *stmt = 0;
  const sqlite3_tokenizer_module *module = &icuTokenizerModule;
  int previous = 0;
  int rc = sqlite3_db_config(db, SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER, -1, &previous);
  if (rc != SQLITE_OK) return rc;
  rc = sqlite3_db_config(db, SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER, 1, 0);
  if (rc == SQLITE_OK) rc = sqlite3_prepare_v2(db, "SELECT fts3_tokenizer('icu', ?)", -1, &stmt, 0);
  if (rc == SQLITE_OK) rc = sqlite3_bind_blob(stmt, 1, &module, sizeof(module), SQLITE_TRANSIENT);
  if (rc == SQLITE_OK) { rc = sqlite3_step(stmt); if (rc == SQLITE_ROW) rc = SQLITE_OK; }
  if (rc != SQLITE_OK) *error = sqlite3_mprintf("Registering FTS4 ICU: %s", sqlite3_errmsg(db));
  sqlite3_finalize(stmt);
  sqlite3_db_config(db, SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER, previous, 0);
  return rc;
}
