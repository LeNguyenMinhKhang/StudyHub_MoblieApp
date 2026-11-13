#!/usr/bin/env bash
# Runs the notification SQL suite against a LOCAL disposable Supabase stack.
# Usage: supabase/tests/notifications/run_local.sh [db-container]
# The container must already contain the base schema (supabase/schema.sql, then the
# migrations in version order). This script re-applies the two notification
# migrations (idempotent) and runs 00-03. 01/02 roll back; 03 cleans up its rows.
set -euo pipefail
container="${1:-supabase_db_studyhub-notification-tests}"
case "$container" in
  supabase_db_*) ;;
  *) echo "Refusing: '$container' is not a local Supabase db container" >&2; exit 2 ;;
esac
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
run() {
  local out status=0
  out="$(docker exec -i "$container" psql -U "$1" -d postgres -v ON_ERROR_STOP=1 -q -At < "$2" 2>&1)" || status=$?
  # Show only results and errors; the expected WARNINGs come from injected send failures.
  grep -E '^(PASS|ERROR|psql:|CONTEXT)' <<< "$out" || true
  if [ "$status" -ne 0 ]; then echo "FAILED: $2" >&2; exit "$status"; fi
}
run postgres "$root/supabase/migrations/20261106120000_notification_center.sql"
run postgres "$root/supabase/migrations/20261106120100_notification_delivery.sql"
for test in 00_fixtures 01_center 02_delivery 03_concurrency; do
  echo "== $test"
  run supabase_admin "$here/$test.sql"
done
