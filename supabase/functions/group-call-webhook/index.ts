// LiveKit webhook target. Deploy with gateway JWT verification disabled (--no-verify-jwt):
// requests are authenticated by the LiveKit signature over the body instead.
import { handleWebhook } from "../_shared/group_calls.ts";
import { callDeps } from "../_shared/call_deps.ts";

const deps = callDeps();
Deno.serve((request) => handleWebhook(request, deps));
