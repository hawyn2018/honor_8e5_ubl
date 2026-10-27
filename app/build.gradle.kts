plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.honor.bootforge"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.hawyn.honor8e5ubl"
        minSdk = 28
        targetSdk = 37
        versionCode = 38
        versionName = "2.5.0"
        manifestPlaceholders["appLabel"] = "hnubl"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_PARTITION_WRITES", "false")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // Installation identity is independent from the internal Kotlin namespace.
            // Keep the requested release package exact and preserve older apps alongside it.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "ALLOW_PARTITION_WRITES", "true")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/fsprobeJni").get().asFile)
    // bfexec / cred_target 编译产物以 assets/magic/ 打包，与 MagicRoot 预置资产同目录部署。
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/magicAssets").get().asFile)
}

val compileFsProbe = tasks.register<Exec>("compileFsProbe") {
    val source = layout.projectDirectory.file("src/main/cpp/fsprobe.c")
    val output = layout.buildDirectory.file("generated/fsprobeJni/arm64-v8a/libbootforge_fsprobe.so")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
            ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                File(it, "toolchains/llvm/prebuilt").isDirectory
            } ?: error("Android NDK is required to compile the read-only geometry helper")
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK LLVM toolchain is missing")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
        environment("TMP", temp.absolutePath)
        environment("TEMP", temp.absolutePath)
        commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-static", source.asFile.absolutePath, "-o", target.absolutePath)
    }
}
tasks.named("preBuild").configure { dependsOn(compileFsProbe) }

val compilePartitionWorker = tasks.register<Exec>("compilePartitionWorker") {
    val source = layout.projectDirectory.file("src/main/cpp/partition_worker.c")
    val output = layout.buildDirectory.file("generated/fsprobeJni/arm64-v8a/libbootforge_partition_worker.so")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
            ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                File(it, "toolchains/llvm/prebuilt").isDirectory
            } ?: error("Android NDK is required to compile the partition worker")
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK LLVM toolchain is missing")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
        environment("TMP", temp.absolutePath)
        environment("TEMP", temp.absolutePath)
        commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-static", source.asFile.absolutePath, "-o", target.absolutePath)
    }
}
tasks.named("preBuild").configure { dependsOn(compilePartitionWorker) }

val compileDmProbe = tasks.register<Exec>("compileDmProbe") {
    val source = layout.projectDirectory.file("src/main/cpp/dmprobe.c")
    val output = layout.buildDirectory.file("generated/fsprobeJni/arm64-v8a/libbootforge_dmprobe.so")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
            ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                File(it, "toolchains/llvm/prebuilt").isDirectory
            } ?: error("Android NDK is required to compile the read-only DM query helper")
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK LLVM toolchain is missing")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
        environment("TMP", temp.absolutePath)
        environment("TEMP", temp.absolutePath)
        commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-static", source.asFile.absolutePath, "-o", target.absolutePath)
    }
}

tasks.named("preBuild").configure { dependsOn(compileDmProbe) }

val compileBfExec = tasks.register<Exec>("compileBfExec") {
    val source = layout.projectDirectory.file("src/main/cpp/bfexec.c")
    val output = layout.buildDirectory.file("generated/magicAssets/magic/bfexec")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
            ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                File(it, "toolchains/llvm/prebuilt").isDirectory
            } ?: error("Android NDK is required to compile the MagicRoot grant executor")
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK LLVM toolchain is missing")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
        environment("TMP", temp.absolutePath)
        environment("TEMP", temp.absolutePath)
        commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-static", source.asFile.absolutePath, "-o", target.absolutePath)
    }
}
tasks.named("preBuild").configure { dependsOn(compileBfExec) }

val compileCredTarget = tasks.register<Exec>("compileCredTarget") {
    val source = layout.projectDirectory.file("src/main/cpp/cred_target.c")
    val output = layout.buildDirectory.file("generated/magicAssets/magic/cred_target")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
            ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                File(it, "toolchains/llvm/prebuilt").isDirectory
            } ?: error("Android NDK is required to compile the MagicRoot cred helper")
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK LLVM toolchain is missing")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
        environment("TMP", temp.absolutePath)
        environment("TEMP", temp.absolutePath)
        commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-O2", "-static", source.asFile.absolutePath, "-o", target.absolutePath)
    }
}
tasks.named("preBuild").configure { dependsOn(compileCredTarget) }

val compileGptTool = tasks.register<Exec>("compileGptTool") {
    val source = layout.projectDirectory.file("src/main/cpp/gpttool.c")
    val output = layout.buildDirectory.file("generated/fsprobeJni/arm64-v8a/libbootforge_gpttool.so")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
            ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                File(it, "toolchains/llvm/prebuilt").isDirectory
            } ?: error("Android NDK is required to compile the GPT snapshot tool")
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK LLVM toolchain is missing")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
        environment("TMP", temp.absolutePath)
        environment("TEMP", temp.absolutePath)
        commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-static", source.asFile.absolutePath, "-o", target.absolutePath)
    }
}

tasks.named("preBuild").configure { dependsOn(compileGptTool) }

dependencies {
    implementation(project(":abl-efisp-core"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader:0.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    testImplementation("junit:junit:4.13.2")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.lifecycle") useVersion("2.11.0")
    }
}
