// Scheduler-only: POST with `Authorization: Bearer <GROUP_DOCUMENT_CLEANUP_SECRET>`.
// Deploy with --no-verify-jwt; the shared secret is checked in constant time.
import { handleCleanup } from "../_shared/group_documents.ts";
import { cleanupDeps } from "../_shared/supabase_deps.ts";

const deps = cleanupDeps();
Deno.serve((request) => handleCleanup(request, deps));
