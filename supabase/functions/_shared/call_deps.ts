// Deno-only wiring for the group call functions. Pinned versions; LiveKit credentials, the
// service-role key and the worker secret come from server environment variables only.
import * as livekit from "npm:livekit-server-sdk@2.19.3";
import { createClient } from "npm:@supabase/supabase-js@2.118.0";
import type { CallDeps, CallProvider } from "./group_calls.ts";
import { createLiveKitProvider } from "./livekit_provider.ts";

export function callDeps(): CallDeps {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("missing server configuration");
  const client = createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
  let provider: CallProvider | null = null;
  return {
    async verifyUser(jwt) {
      const { data, error } = await client.auth.getUser(jwt);
      return error ? null : data.user?.id ?? null;
    },
    rpc: (name, args) => client.rpc(name, args) as never,
    provider: () => provider ??= createLiveKitProvider(livekit, {
      url: Deno.env.get("LIVEKIT_URL") ?? "",
      apiKey: Deno.env.get("LIVEKIT_API_KEY") ?? "",
      apiSecret: Deno.env.get("LIVEKIT_API_SECRET") ?? "",
    }),
    now: () => Date.now(),
    workerSecret: Deno.env.get("GROUP_CALL_WORKER_SECRET"),
  };
}
