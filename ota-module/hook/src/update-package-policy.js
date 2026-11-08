/** Only the updater's own hot-only predicate can opt out of the A/B workflow. */
export function createUpdatePackagePolicy({enabled, readHotpatchOnly, warn}) {
  let protectedPayloadSeen = false;
  let probing = false;
  const canPreferPatch = () => enabled() && !protectedPayloadSeen;

  return {
    canPreferPatch,
    beginProtectedPayload() { protectedPayloadSeen = true; },
    isHotpatchOnly() {
      if (!canPreferPatch() || probing) return false;
      probing = true;
      try {
        const nativeProof = readHotpatchOnly() === true;
        // A Binder payload may have started while the native query was running.
        return nativeProof && !protectedPayloadSeen;
      } catch (error) {
        warn(error);
        return false;
      } finally {
        probing = false;
      }
    },
  };
}
