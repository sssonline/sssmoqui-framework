#! /usr/bin/env bash
# Copy the moquitest database (loaded + flow-suite demo data) over the dev database, so you can poke around the
# data the aspen test suite generates without running tests directly against dev (the suites refuse to: TESTING.md).
# This OVERWRITES your dev database. Your previous dev database is backed up first, never discarded.
#   ./copyTestToDev.sh              h2 and Postgres (whichever is present)
#   ./copyTestToDev.sh h2
#   ./copyTestToDev.sh postgres
#   ./copyTestToDev.sh --yes        skip the confirmation prompt
set -e
cd "$(dirname "$0")" || exit 1

TARGET=all
ASSUME_YES=false
for arg in "$@"; do
  case "$arg" in
    h2|postgres) TARGET="$arg" ;;
    --yes|-y) ASSUME_YES=true ;;
    *) echo "usage: ./copyTestToDev.sh [h2|postgres] [--yes]"; exit 1 ;;
  esac
done

dev_app_running() {
  ps -eo command | grep '[m]oqui.war' | grep -qv ' load'
}

STAMP=$(date +%Y%m%d-%H%M%S)

copy_h2() {
  local h2_dir="runtime/db/h2"
  local test_db="$h2_dir/moquitest.mv.db"
  local dev_db="$h2_dir/moqui.mv.db"
  if [ ! -f "$test_db" ]; then
    echo "H2: no $test_db yet - run ./aspenTest.sh (h2) at least once first"
    return 1
  fi
  if [ -f "$dev_db" ]; then
    cp "$dev_db" "$h2_dir/moqui.mv.db.bak-$STAMP"
    echo "H2: backed up current dev database to $h2_dir/moqui.mv.db.bak-$STAMP"
  fi
  cp "$test_db" "$dev_db"
  echo "H2: moquitest copied over moqui. Reindex search if you use it; instance_purpose still comes from the conf you boot with, not the file."
}

# same connection convention as cleanPostgres.sh: local Postgres on 127.0.0.1:5432, Docker container
# moqui-postgres if there's no local server answering there.
pg_cmd() {
  if docker info >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -qx moqui-postgres; then
    echo "docker exec -i moqui-postgres psql -U moqui -d postgres -v ON_ERROR_STOP=1"
  else
    echo "psql -h 127.0.0.1 -p 5432 -U moqui -d postgres -v ON_ERROR_STOP=1"
  fi
}

copy_postgres() {
  unset PGHOST PGHOSTADDR PGPORT PGUSER PGPASSWORD PGDATABASE PGSERVICE PGSERVICEFILE PGPASSFILE
  local PSQL
  PSQL=$(pg_cmd)
  # a bare "copy_postgres || rc=1" at the call site turns off -e for everything run in here, so every
  # step below checks its own exit code explicitly instead of relying on set -e to stop on failure.
  if ! $PSQL -tAc "SELECT 1 FROM pg_database WHERE datname='moquitest'" | grep -q 1; then
    echo "Postgres: no moquitest database yet - run ./aspenTest.sh (postgres) at least once first"
    return 1
  fi
  # a fresh CREATE DATABASE ... TEMPLATE needs no other connections on either side
  if ! $PSQL -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('moqui','moquitest') AND pid <> pg_backend_pid();"; then
    echo "Postgres: couldn't clear connections on moqui/moquitest - aborting, moqui database untouched"
    return 1
  fi
  if $PSQL -tAc "SELECT 1 FROM pg_database WHERE datname='moqui'" | grep -q 1; then
    # quoted: an unquoted identifier can't contain the "-" in the timestamp (parses as subtraction)
    if ! $PSQL -c "ALTER DATABASE moqui RENAME TO \"moqui_bak_$STAMP\";"; then
      echo "Postgres: couldn't rename the current moqui database aside - aborting, moqui database untouched"
      return 1
    fi
    echo "Postgres: renamed current dev database to moqui_bak_$STAMP"
  fi
  if ! $PSQL -c "CREATE DATABASE moqui TEMPLATE moquitest OWNER moqui;"; then
    echo "Postgres: CREATE DATABASE failed after the old one was renamed aside - your old data is safe as \"moqui_bak_$STAMP\"," \
         "but moqui may not exist right now. To put it back: psql -c 'ALTER DATABASE \"moqui_bak_$STAMP\" RENAME TO moqui;'"
    return 1
  fi
  echo "Postgres: moquitest copied into moqui."
}

echo "About to copy moquitest over your dev database ($TARGET). Your current dev database is backed up, not discarded."
if dev_app_running; then
  echo "WARNING: a Moqui app is running from moqui.war. Stop it first - it holds the dev database open."
fi
if [ "$ASSUME_YES" != true ]; then
  read -r -p "Continue? [y/N] " reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "Cancelled."; exit 1 ;;
  esac
fi

rc=0
if [ "$TARGET" = h2 ] || [ "$TARGET" = all ]; then
  copy_h2 || rc=1
fi
if [ "$TARGET" = postgres ] || [ "$TARGET" = all ]; then
  copy_postgres || rc=1
fi
exit $rc
