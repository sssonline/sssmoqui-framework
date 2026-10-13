#! /usr/bin/env bash
set -e
# (Re)create an empty local 'moqui' Postgres database for devPostgres.sh / MoquiDevConfPostgres.xml.
# Uses a local psql if one is installed, otherwise a Docker container named moqui-postgres (created on first run).
# max_prepared_transactions is needed for Moqui's XA transactions (pool-maxsize for postgres is 90).
if command -v psql >/dev/null; then
  psql -d postgres -c "DO \$\$BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='moqui') THEN CREATE ROLE moqui LOGIN PASSWORD 'moqui'; END IF; END\$\$;"
  psql -d postgres -c "DROP DATABASE IF EXISTS moqui;" -c "CREATE DATABASE moqui OWNER moqui;"
else
  if ! docker info >/dev/null 2>&1; then echo "Docker is not running (start Docker Desktop or run: colima start)"; exit 1; fi
  if ! docker ps -a --format '{{.Names}}' | grep -qx moqui-postgres; then
    docker run -d --name moqui-postgres -p 127.0.0.1:5432:5432 -e POSTGRES_USER=moqui -e POSTGRES_PASSWORD=moqui \
      -e POSTGRES_DB=postgres postgres:17 -c max_prepared_transactions=100
    for i in $(seq 60); do docker exec moqui-postgres pg_isready -h 127.0.0.1 -U moqui -d postgres >/dev/null 2>&1 && break; sleep 1; done
  else
    docker start moqui-postgres >/dev/null
    for i in $(seq 60); do docker exec moqui-postgres pg_isready -h 127.0.0.1 -U moqui -d postgres >/dev/null 2>&1 && break; sleep 1; done
  fi
  docker exec moqui-postgres psql -U moqui -d postgres -c "DROP DATABASE IF EXISTS moqui;" -c "CREATE DATABASE moqui OWNER moqui;"
fi
