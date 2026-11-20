// Revocation/end-room worker and room reconciliation. Called by the 5-second scheduler and by
// the post-commit wake (pg_net) with "Authorization: Bearer <GROUP_CALL_WORKER_SECRET>".
// Deploy with --no-verify-jwt; the shared secret is checked in constant time.
import { handleWorker } from "../_shared/group_calls.ts";
import { callDeps } from "../_shared/call_deps.ts";

const deps = callDeps();
Deno.serve((request) => handleWorker(request, deps));
