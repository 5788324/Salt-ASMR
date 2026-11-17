plugins {
    kotlin("jvm") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "io.github.saltasmr"
version = "0.1.0-alpha.1"

kotlin { jvmToolchain(25) }

val desktopRuntime = configurations.create("desktopRuntime")

dependencies {
    compileOnly(kotlin("stdlib"))
    // This plugin uses only SpwPlugin / PluginContext, not the API's Compose hook types.
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    add(desktopRuntime.name, kotlin("stdlib"))
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("saltasmr.uiTest", System.getProperty("saltasmr.uiTest", "false"))
}
tasks.register<JavaExec>("runDesktop") {
    group = "application"
    description = "在独立测试数据目录打开作品管理窗口（无需 Salt）"
    classpath = sourceSets.main.get().runtimeClasspath + desktopRuntime
    mainClass.set("io.github.saltasmr.DesktopMainKt")
    systemProperty("saltasmr.dataDir", providers.gradleProperty("dataDir")
        .getOrElse(layout.buildDirectory.dir("desktop-data").get().asFile.absolutePath))
}

spmod {
    PluginClass = "io.github.saltasmr.SaltAsmrPlugin"
    PluginId = "io.github.saltasmr"
    PluginName = "Salt-ASMR 作品收藏与歌单"
    PluginVersion = project.version.toString()
    PluginProvider = "Salt-ASMR contributors"
    PluginDescription = "本地 RJ 作品收藏与作品歌单；只读音频文件"
    PluginHasConfig = true
    PluginOpenSourceUrl = "https://github.com/5788324/Salt-ASMR"
}
