package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.ModuleRootAccess
import dev.honor.bootforge.core.CustomSuSettingsStore
import dev.honor.bootforge.core.RootShell

class HonorUblApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val customSu = CustomSuSettingsStore.inDirectory(filesDir)
        RootShell.configureCustomSuSettings(customSu::read)
        ModuleRootAccess.configure(this)
        KernelRootBindings.create().forEach { (chain, adapter) -> RootIntegration.register(chain, adapter) }
    }
}
