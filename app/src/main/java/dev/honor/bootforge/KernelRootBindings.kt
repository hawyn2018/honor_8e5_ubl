package dev.honor.bootforge

/** The Application and routing tests use exactly the same per-chain wiring. */
internal object KernelRootBindings {
    fun create(): Map<RootChain, KernelDispatchRootAdapter> = linkedMapOf(
        RootChain.CVE_2026_98260 to KernelDispatchRootAdapter(
            RootChain.CVE_2026_98260, Root98260Adapter, NewKernel98260Adapter, G14298260Adapter),
        RootChain.CVE_2026_64650 to KernelDispatchRootAdapter(
            RootChain.CVE_2026_64650, Root64650Adapter, NewKernel64650Adapter, G14264650Adapter),
    )
}
