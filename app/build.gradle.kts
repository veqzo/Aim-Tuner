plugins {
    id("aimtune.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":games"))
    implementation(project(":input"))
    implementation(project(":storage"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.gdx.freetype)
    implementation(libs.lwjgl.tinyfd)
    runtimeOnly(variantOf(libs.lwjgl.tinyfd) { classifier("natives-windows") })
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.veqzo.aimtune.app.MainKt")
}

// Renders every screen with sample data into app/build/screenshots (hidden window, no user data touched).
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Saves a PNG of every UI screen to build/screenshots for comparison with docs/design."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("--screenshots", layout.buildDirectory.dir("screenshots").get().asFile.path)
}

tasks.register<JavaExec>("screenshotsDe") {
    group = "verification"
    description = "Saves a PNG of every UI screen in German to build/screenshots-de."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("--screenshots", layout.buildDirectory.dir("screenshots-de").get().asFile.path, "--language", "de")
}

// The same tour in a 1280x720 window, to check that nothing is clipped on a small screen.
tasks.register<JavaExec>("screenshots720") {
    group = "verification"
    description = "Saves a PNG of every UI screen at 1280x720 to build/screenshots-720."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("--screenshots", layout.buildDirectory.dir("screenshots-720").get().asFile.path, "--window-size", "1280x720")
}

tasks.register<JavaExec>("screenshots720De") {
    group = "verification"
    description = "Saves a PNG of every UI screen in German at 1280x720 to build/screenshots-720-de."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("--screenshots", layout.buildDirectory.dir("screenshots-720-de").get().asFile.path, "--language", "de", "--window-size", "1280x720")
}

// The UI tokens live once in docs/design and are bundled into the app from there.
// The version of this build (-PappVersion from the release tag) for the update check; dev runs show the default.
val appVersionName = providers.gradleProperty("appVersion").orElse("0.1.0")

tasks.processResources {
    // A local val, so the lambda below captures the provider and not the build script (configuration cache).
    val version = appVersionName
    inputs.property("appVersion", version)
    filesMatching("version.properties") { expand("appVersion" to version.get()) }
    from(rootProject.file("docs/design/tokens.json")) { into("assets/ui") }
}

// Windows app image or installer with its own trimmed Java runtime, built with the JDK's jpackage (see ADR 0005).
// `./gradlew :app:jpackage` makes build/jpackage/AimTune/AimTune.exe; `-PinstallerType=exe` or `msi` needs the WiX toolset.
interface Injected {
    @get:javax.inject.Inject val fileSystem: FileSystemOperations
}

val jpackageRuntimeModules = "java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.net.http,jdk.unsupported"

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Packages AimTune with a bundled runtime using jpackage."
    val install = tasks.installDist
    dependsOn(install)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val outDir = layout.buildDirectory.dir("jpackage")
    val type = providers.gradleProperty("installerType").orElse("app-image")
    // The release workflow passes the tag (v1.2.3 -> 1.2.3); jpackage needs a numeric version.
    val appVersion = appVersionName.get()
    // Gradle's own delete also removes the read-only files jpackage leaves; the service is injected for the configuration cache.
    val fileSystem = objects.newInstance<Injected>().fileSystem
    doFirst { fileSystem.delete { delete(outDir) } }
    executable(launcher.map { it.metadata.installationPath.file("bin/jpackage.exe").asFile.path }.get())
    args(
        "--type", type.get(),
        "--name", "AimTune",
        "--app-version", appVersion,
        "--vendor", "AimTune contributors",
        "--description", "Finds the mouse sensitivity you aim best with",
        "--input", install.get().destinationDir.resolve("lib").path,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", application.mainClass.get(),
        "--add-modules", jpackageRuntimeModules,
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--dest", outDir.get().asFile.path,
    )
    if (type.get() != "app-image") args("--win-menu", "--win-shortcut", "--win-dir-chooser")
}
