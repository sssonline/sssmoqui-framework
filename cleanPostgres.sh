#! /usr/bin/env bash
set -e
# (Re)create an empty LOCAL 'moqui' Postgres database for devPostgres.sh / MoquiDevConfPostgres.xml.
# Always targets 127.0.0.1:5432 and ignores PG* environment variables, so it can never reach a remote server.
# Uses the Docker container moqui-postgres (created on first run) unless there is no such container and a
# non-Docker Postgres is already answering on 127.0.0.1:5432 (e.g. Postgres.app / Homebrew).
# max_prepared_transactions is needed for Moqui's XA transactions (pool-maxsize for postgres is 90).
# Stop any running Moqui first: DROP DATABASE fails while it holds connections.
unset PGHOST PGHOSTADDR PGPORT PGUSER PGPASSWORD PGDATABASE PGSERVICE PGSERVICEFILE PGPASSFILE

# keep in step with the Azure server's major version
PG_IMAGE=postgres:18

wait_ready() {
  for i in $(seq 60); do docker exec moqui-postgres pg_isready -h 127.0.0.1 -U moqui -d postgres >/dev/null 2>&1 && return 0; sleep 1; done
  echo "Postgres in container moqui-postgres not ready after 60s (check: docker logs moqui-postgres)"; exit 1
}

hasContainer=false
if docker info >/dev/null 2>&1 && docker ps -a --format '{{.Names}}' | grep -qx moqui-postgres; then hasContainer=true; fi

if [ "$hasContainer" = false ] && command -v pg_isready >/dev/null && pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then
  PSQL="psql -h 127.0.0.1 -p 5432 -d postgres -v ON_ERROR_STOP=1"
  $PSQL -c "DO \$\$BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='moqui') THEN CREATE ROLE moqui LOGIN PASSWORD 'moqui'; END IF; END\$\$;"
  if [ "$($PSQL -tAc 'show max_prepared_transactions')" = "0" ]; then
    echo "WARNING: max_prepared_transactions is 0 on the local server; set it to 100 and restart Postgres or Moqui XA will fail"
  fi
  $PSQL -c "DROP DATABASE IF EXISTS moqui;" -c "CREATE DATABASE moqui OWNER moqui;"
  exit 0
fi

if ! docker info >/dev/null 2>&1; then echo "Docker is not running (start Docker Desktop or run: colima start)"; exit 1; fi
if [ "$hasContainer" = false ]; then
  docker run -d --name moqui-postgres -p 127.0.0.1:5432:5432 -e POSTGRES_USER=moqui -e POSTGRES_PASSWORD=moqui \
    -e POSTGRES_DB=postgres $PG_IMAGE -c max_prepared_transactions=100
else
  img=$(docker inspect -f '{{.Config.Image}}' moqui-postgres)
  if [ "$img" != "$PG_IMAGE" ]; then
    echo "moqui-postgres runs $img but this script expects $PG_IMAGE."
    echo "Recreate it (drops the local dev DB, then reload): docker rm -f moqui-postgres && ./cleanPostgres.sh"
    exit 1
  fi
  docker start moqui-postgres >/dev/null
fi
wait_ready
docker exec moqui-postgres psql -U moqui -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS moqui;" -c "CREATE DATABASE moqui OWNER moqui;"
