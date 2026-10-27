package dev.honor.bootforge

import android.app.Application

class HonorUblApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RootIntegration.register(RootChain.CVE_2026_98260, Root98260Adapter)
        RootIntegration.register(RootChain.CVE_2026_64650, Root64650Adapter)
    }
}
