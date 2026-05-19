import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Strip classes we replace in app/src (avoids duplicate WebRtcAudioRecord / JavaAudioDeviceModule). */
val streamWebrtcVersion = "1.3.9"
val patchedGroup = "com.example.patched"
val patchedArtifact = "stream-webrtc-android-patched"
val patchedVersion = "$streamWebrtcVersion-patched"
val streamWebrtcOriginalArtifact: Configuration = configurations.create("streamWebrtcOriginalArtifact") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}

dependencies {
    add(streamWebrtcOriginalArtifact.name, "io.getstream:stream-webrtc-android:$streamWebrtcVersion") {
        isTransitive = false
    }
}

val patchedStreamWebrtcAar =
    layout.buildDirectory.file("patched-deps/stream-webrtc-android-$streamWebrtcVersion-patched.aar")
val localMavenRepoDir = rootProject.layout.buildDirectory.dir("local-maven")

val patchStreamWebrtcAar = tasks.register("patchStreamWebrtcAar") {
    group = "build"
    description =
        "Remove org.webrtc.audio.WebRtcAudioRecord / JavaAudioDeviceModule from stream-webrtc AAR (app supplies patched sources)."
    inputs.files(streamWebrtcOriginalArtifact.incoming.files)
    outputs.file(patchedStreamWebrtcAar)

    fun shouldDropClass(entryName: String): Boolean {
        if (!entryName.endsWith(".class")) return false
        if (!entryName.startsWith("org/webrtc/audio/")) return false
        val base = entryName.removePrefix("org/webrtc/audio/").removeSuffix(".class")
        return base == "WebRtcAudioRecord" ||
            base.startsWith("WebRtcAudioRecord\$") ||
            base == "JavaAudioDeviceModule" ||
            base.startsWith("JavaAudioDeviceModule\$")
    }

    fun filterClassesJar(jarBytes: ByteArray): ByteArray {
        val outJar = ByteArrayOutputStream()
        ZipOutputStream(outJar).use { zos ->
            ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !shouldDropClass(entry.name)) {
                        val data = zis.readBytes()
                        val newEntry = ZipEntry(entry.name)
                        newEntry.time = entry.time
                        zos.putNextEntry(newEntry)
                        zos.write(data)
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return outJar.toByteArray()
    }

    doLast {
        val resolvedFiles = streamWebrtcOriginalArtifact.incoming.files.files
        val aarFiles = resolvedFiles.filter { it.extension.equals("aar", ignoreCase = true) }
        require(aarFiles.size == 1) {
            "Expected exactly one stream-webrtc AAR, got ${aarFiles.size}. Resolved files: $resolvedFiles"
        }
        val inputAar = aarFiles.single()
        val outFile = patchedStreamWebrtcAar.get().asFile
        outFile.parentFile?.mkdirs()

        val repacked = ByteArrayOutputStream()
        ZipOutputStream(repacked).use { outZip ->
            ZipFile(inputAar).use { aarZip ->
                val entries = aarZip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val bytes = aarZip.getInputStream(e).readBytes()
                    val newEntry = ZipEntry(e.name)
                    newEntry.time = e.time
                    outZip.putNextEntry(newEntry)
                    if (e.name == "classes.jar") {
                        outZip.write(filterClassesJar(bytes))
                    } else {
                        outZip.write(bytes)
                    }
                    outZip.closeEntry()
                }
            }
        }
        FileOutputStream(outFile).use { it.write(repacked.toByteArray()) }
    }
}

val publishPatchedStreamWebrtcToLocalMaven = tasks.register("publishPatchedStreamWebrtcToLocalMaven") {
    group = "build"
    description = "Publish patched stream-webrtc AAR into build/local-maven as a normal Maven artifact."
    dependsOn(patchStreamWebrtcAar)
    outputs.dir(localMavenRepoDir)

    doLast {
        val repo = localMavenRepoDir.get().asFile
        val moduleDir = repo.resolve(
            "${patchedGroup.replace('.', '/')}/$patchedArtifact/$patchedVersion"
        )
        moduleDir.mkdirs()

        val aarFile = patchedStreamWebrtcAar.get().asFile
        val targetAar = moduleDir.resolve("$patchedArtifact-$patchedVersion.aar")
        aarFile.copyTo(targetAar, overwrite = true)

        val pom = moduleDir.resolve("$patchedArtifact-$patchedVersion.pom")
        pom.writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$patchedGroup</groupId>
              <artifactId>$patchedArtifact</artifactId>
              <version>$patchedVersion</version>
              <packaging>aar</packaging>
            </project>
            """.trimIndent()
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(publishPatchedStreamWebrtcToLocalMaven)
}

android {
    namespace = "com.example.screencast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.screencast"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-beta1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // WebRTC: patched AAR published to local Maven + app-local org.webrtc.audio.* overrides
    implementation("$patchedGroup:$patchedArtifact:$patchedVersion")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // OkHttp (WebSocket signaling)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.15.0")
}
