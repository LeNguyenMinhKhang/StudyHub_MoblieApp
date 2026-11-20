// Deno-only wiring for the Edge entrypoints. Versions are pinned; the service-role key
// and scheduler secret come from server environment variables and never reach Android.
import { createClient } from "npm:@supabase/supabase-js@2.118.0";
import { PDFDocument } from "npm:pdf-lib@1.17.1";
import type { CleanupDeps, FinalizeDeps } from "./group_documents.ts";

function serviceClient() {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("missing server configuration");
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

export function finalizeDeps(): FinalizeDeps {
  const client = serviceClient();
  return {
    async verifyUser(jwt) {
      const { data, error } = await client.auth.getUser(jwt);
      return error ? null : data.user?.id ?? null;
    },
    rpc: (name, args) => client.rpc(name, args) as never,
    async download(bucket, key) {
      const { data, error } = await client.storage.from(bucket).download(key);
      if (error) {
        const status = (error as { statusCode?: string | number }).statusCode;
        if (String(status) === "404" || /not found/i.test(error.message)) return null;
        throw error;
      }
      return new Uint8Array(await data.arrayBuffer());
    },
    async remove(bucket, keys) {
      const { error } = await client.storage.from(bucket).remove(keys);
      return { error: error ? { message: "storage error" } : null };
    },
    loadPdf: (bytes, options) => PDFDocument.load(bytes, options),
  };
}

export function cleanupDeps(): CleanupDeps {
  const deps = finalizeDeps();
  return { rpc: deps.rpc, remove: deps.remove, secret: Deno.env.get("GROUP_DOCUMENT_CLEANUP_SECRET") };
}
