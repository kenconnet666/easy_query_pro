#!/usr/bin/env bash
set -euo pipefail

hdbsql=/usr/sap/HXE/HDB90/exe/hdbsql
credentials=(-u SYSTEM -p "${EQP_HANA_PASSWORD:?Set EQP_HANA_PASSWORD}")
"$hdbsql" -n 127.0.0.1:39017 "${credentials[@]}" -E 1 -I /eqp/enable-hdi.sql

for ((attempt = 0; attempt < 30; attempt++)); do
  ready=$("$hdbsql" -n 127.0.0.1:39041 "${credentials[@]}" -x -a -quiet -nocmdstats -E 1 \
    "SELECT COUNT(*) FROM SYS.PROCEDURES WHERE SCHEMA_NAME = '_SYS_DI' AND PROCEDURE_NAME = 'CREATE_CONTAINER'")
  if [[ "$ready" =~ ^[[:space:]]*1[[:space:]]*$ ]]; then
    printf '%s\n' 'HXE HDI service and SQL API are ready.'
    exit 0
  fi
  sleep 1
done

printf '%s\n' 'HXE HDI SQL API was not ready within 30 seconds.' >&2
exit 1
