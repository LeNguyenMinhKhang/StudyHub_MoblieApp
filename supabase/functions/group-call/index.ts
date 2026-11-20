// POST { action: "start_or_join", group_id, request_id, client_session_id, mode: "audio"|"video" }
//    | { action: "leave" | "end", call_id } with the member's JWT (verified via auth.getUser).
import { handleCall } from "../_shared/group_calls.ts";
import { callDeps } from "../_shared/call_deps.ts";

const deps = callDeps();
Deno.serve((request) => handleCall(request, deps));
