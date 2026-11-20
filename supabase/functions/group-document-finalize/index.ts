// POST { upload_id } with the member's JWT. JWT verification is done inside the handler
// (auth.getUser), so the function can be deployed with gateway verification enabled too.
import { handleFinalize } from "../_shared/group_documents.ts";
import { finalizeDeps } from "../_shared/supabase_deps.ts";

const deps = finalizeDeps();
Deno.serve((request) => handleFinalize(request, deps));
