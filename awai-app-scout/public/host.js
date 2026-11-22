// restricted-wasm-v1 trusted shell (KOTOBA-159). This document holds the
// canonical runtime (vendor/, byte-exact and pinned), checks the descriptor,
// verifies the guest document against the SHA-256 pinned at build time, and
// mounts it with mountRestrictedClientApp in an opaque-origin sandbox. The
// guest can only ask for a declared operation through the mount's port; the
// broker opens the consent dialog here, and denying sends nothing. Eligibility
// is the descriptor's own operations and is never taken from the guest.
import { clientRuntimeProblems, createClientConsentHost, createClientOperationBroker, mountRestrictedClientApp, verifyClientArtifact } from "./vendor/kotoba-client-runtime.js";
import host from "./host-data.js";

const container = document.getElementById("app");
try {
  const { descriptor, artifact: declaration, guest } = host;
  const capabilities = [...new Set(descriptor.operations.map((operation) => operation.capability))];
  const problems = clientRuntimeProblems(descriptor, capabilities, location.origin);
  if (problems.length > 0) throw new Error(`canonical client runtime refused descriptor: ${JSON.stringify(problems)}`);
  const broker = createClientOperationBroker({
    runtime: descriptor,
    requestedCapabilities: capabilities,
    approvedOperations: descriptor.operations,
    hostSupportedOperations: descriptor.operations.map((operation) => [operation.capability, operation.service, operation.operation]),
    consentHost: createClientConsentHost({ locale: "ja" }),
  });
  const artifact = await verifyClientArtifact(new TextEncoder().encode(guest), declaration);
  await mountRestrictedClientApp({ artifact, container, broker });
} catch (error) {
  const problem = document.createElement("p");
  problem.className = "problem";
  problem.textContent = `この app を開けません: ${String(error?.message ?? error)}`;
  container.replaceChildren(problem);
}
