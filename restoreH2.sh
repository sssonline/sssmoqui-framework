#!/usr/bin/env bash

if [ -z "$MOQUI_HOME" ]; then
  MOQUI_HOME=~/src/moqui
fi

backup_root=~/src/MoquiBackup/MoquiH2Backup 
gradle cleanDb
cp -R ${backup_root}/backup$1/db/derby ${MOQUI_HOME}/runtime/db/
cp -R ${backup_root}/backup$1/db/h2 ${MOQUI_HOME}/runtime/db/
cp -R ${backup_root}/backup$1/db/orientdb ${MOQUI_HOME}/runtime/db/
