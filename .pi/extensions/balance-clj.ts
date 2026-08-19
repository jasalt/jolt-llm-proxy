// @ts-nocheck -- Pi supplies these runtime modules.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { extname, resolve } from "node:path";

/** Run Jolt's reader-aware delimiter fixer after Clojure source tools run. */
export default function (pi: ExtensionAPI) {
  const pendingFiles = new Set<string>();

  pi.on("tool_result", (event, ctx) => {
    if (event.isError || !["read", "edit", "write"].includes(event.toolName)) return;

    const path = (event.input as { path?: unknown }).path;
    if (typeof path !== "string") return;

    const file = resolve(ctx.cwd, path.replace(/^@/, ""));
    if (extname(file) === ".clj") pendingFiles.add(file);
  });

  pi.on("turn_end", async (_event, ctx) => {
    const files = [...pendingFiles];
    pendingFiles.clear();

    for (const file of files) {
      const result = await pi.exec("brepl", ["balance", file], { timeout: 10_000 });
      if (result.code !== 0) {
        ctx.ui.notify(
          `brepl balance failed for ${file}: ${result.stderr || result.stdout}`,
          "error",
        );
      }
    }
  });
}
