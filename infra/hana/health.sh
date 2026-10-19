#!/usr/bin/env bash
set -euo pipefail

/usr/sap/HXE/HDB90/exe/hdbsql -n 127.0.0.1:39017 -d HXE -u SYSTEM \
  -p "${EQP_HANA_PASSWORD:?Set EQP_HANA_PASSWORD}" 'SELECT 1 FROM DUMMY' >/dev/null 2>&1
# A restart can leave an unused bootstrap file; remove it only after authenticated SQL succeeds.
rm -f /run/eqp/password.json
