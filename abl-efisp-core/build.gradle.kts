import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}


tasks.test { useJUnit() }

// Focused verification after rebuilding the bundled native payload. The full
// inherited test report remains under test, rather than being overwritten by
// a filtered invocation against the same task's report directory.
tasks.register<Test>("kblIntegrationTest") {
    useJUnit()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "1g"
    maxParallelForks = 1
    filter {
        includeTestsMatching("*Kbl*")
        includeTestsMatching("*AblEfisp*")
    }
}

dependencies {
    implementation("org.tukaani:xz:1.12")
    testImplementation("junit:junit:4.13.2")

}
