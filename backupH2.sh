#!/usr/bin/env bash

if [ -z "$MOQUI_HOME" ]; then
  MOQUI_HOME=~/src/moqui
fi

backup_root=~/src/MoquiBackup/MoquiH2Backup
rm -rf ${backup_root}/backup$1
mkdir -p ${backup_root}/backup$1/db
cp -R $MOQUI_HOME/runtime/db/derby ${backup_root}/backup$1/db
cp -R $MOQUI_HOME/runtime/db/h2 ${backup_root}/backup$1/db
cp -R $MOQUI_HOME/runtime/db/orientdb ${backup_root}/backup$1/db
