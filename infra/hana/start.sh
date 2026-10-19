#!/usr/bin/env bash
set -euo pipefail

password=${EQP_HANA_PASSWORD:?Set EQP_HANA_PASSWORD}
if [[ ! $password =~ ^[a-zA-Z0-9]{16,64}$ || ! $password =~ [a-z] || ! $password =~ [A-Z] || ! $password =~ [0-9] ]]; then
  echo 'HANA bootstrap password must contain 16-64 letters and digits, including all three character classes.' >&2
  exit 1
fi

# The SAP entrypoint requires a JSON file. Keep it only in a private tmpfs.
umask 077
printf '{"master_password":"%s"}\n' "$password" > /run/eqp/password.json
unset password EQP_HANA_PASSWORD
exec /run_hana --passwords-url file:///run/eqp/password.json --agree-to-sap-license
