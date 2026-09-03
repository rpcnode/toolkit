import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

group = "rpcnode"
version = "0.1.3"

/** Host agent JAR (`rpcnode-agent.jar`). Separate from the server `version`. */
val chainAgentVersion = "0.1.2"

/** Snapshot CDN sync JAR (`rpcnode-cdn.jar`). */
val cdnVersion = "0.2.5"

application {
    mainClass.set("rpcnode.toolkit.panel.presentation.http.ApplicationKt")
    // JDK 25: Netty loads JNI; without this, System::loadLibrary is a warning
    // and will be blocked in a later JDK.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

sourceSets {
    create("agent")
    create("agentTest") {
        compileClasspath += getByName("agent").output + getByName("agent").compileClasspath
        runtimeClasspath += getByName("agent").output + getByName("agent").runtimeClasspath
    }
    create("cdn")
    create("cdnTest") {
        compileClasspath += getByName("cdn").output + getByName("cdn").compileClasspath
        runtimeClasspath += getByName("cdn").output + getByName("cdn").runtimeClasspath
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.logback.classic)
    implementation(libs.directories)
    implementation(libs.appdirs)
    implementation(libs.jbcrypt)
    implementation(libs.sqlite.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.snakeyaml)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Agent JAR is Ktor + logback + directories/appdirs (OS config / log paths). No SQLite, no
    // server. snakeyaml is here only to read the same `chains/<id>/clients.yml` fixed-port catalog the
    // panel ships — the agent needs the port numbers to reserve, not the full client catalog
    // (artifacts, sources, downloads stay panel-only).
    // The Ktor plugin BOM applies to main, not to extra source sets.
    val ktorBom = platform("io.ktor:ktor-bom:${libs.versions.ktor.get()}")
    add("agentImplementation", ktorBom)
    add("agentImplementation", libs.ktor.server.core)
    add("agentImplementation", libs.ktor.server.netty)
    add("agentImplementation", libs.ktor.server.call.logging)
    add("agentImplementation", libs.ktor.server.content.negotiation)
    add("agentImplementation", libs.ktor.serialization.kotlinx.json)
    add("agentImplementation", libs.ktor.client.core)
    add("agentImplementation", libs.ktor.client.cio)
    add("agentImplementation", libs.ktor.client.content.negotiation)
    add("agentImplementation", libs.logback.classic)
    add("agentImplementation", libs.directories)
    add("agentImplementation", libs.appdirs)
    add("agentImplementation", libs.snakeyaml)

    add("agentTestImplementation", ktorBom)
    add("agentTestImplementation", libs.ktor.server.test.host)
    add("agentTestImplementation", libs.kotlin.test.junit5)
    add("agentTestImplementation", libs.kotlinx.coroutines.test)
    add("agentTestImplementation", libs.junit.jupiter)
    add("agentTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")

    // CDN sync JAR: coroutines + json + logback + Mordant (TTY menu). No SQLite or Ktor server.
    add("cdnImplementation", libs.kotlinx.coroutines.core)
    add("cdnImplementation", libs.kotlinx.serialization.json)
    add("cdnImplementation", libs.logback.classic)
    add("cdnImplementation", libs.mordant)

    add("cdnTestImplementation", libs.kotlin.test.junit5)
    add("cdnTestImplementation", libs.kotlinx.coroutines.test)
    add("cdnTestImplementation", libs.junit.jupiter)
    add("cdnTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val agentVersionRes = layout.buildDirectory.dir("generated/agent-version")
val writeAgentVersion = tasks.register("writeAgentVersion") {
    val dir = agentVersionRes
    val ver = provider { chainAgentVersion }
    inputs.property("version", ver)
    outputs.dir(dir)
    doLast {
        val f = dir.get().asFile.resolve("agent/version")
        f.parentFile.mkdirs()
        f.writeText(ver.get().trim() + "\n")
    }
}

sourceSets.named("main") {
    resources.srcDir(writeAgentVersion)
}

sourceSets.named("agent") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
    resources.srcDir(writeAgentVersion)
    // Same chains/<id>/clients.yml the panel ships — agent reads only the fixed ports out of it
    // (see CatalogFixedPortsReader), so the "no catalog in the agent" rule keeps meaning "no
    // artifacts/SQLite/downloads", not "the agent can't know the ports it must reserve".
    resources.srcDir("src/main/resources")
    resources.include("chains/**", "agent/version")
}

sourceSets.named("agentTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

val cdnVersionRes = layout.buildDirectory.dir("generated/cdn-version")
val writeCdnVersion = tasks.register("writeCdnVersion") {
    val dir = cdnVersionRes
    val ver = provider { cdnVersion }
    inputs.property("version", ver)
    outputs.dir(dir)
    doLast {
        val f = dir.get().asFile.resolve("cdn/version")
        f.parentFile.mkdirs()
        f.writeText(ver.get().trim() + "\n")
    }
}

sourceSets.named("cdn") {
    resources.srcDir(writeCdnVersion)
}

val agentTest = tasks.register<Test>("agentTest") {
    description = "Runs host agent tests"
    group = "verification"
    testClassesDirs = sourceSets["agentTest"].output.classesDirs
    classpath = sourceSets["agentTest"].runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val cdnTest = tasks.register<Test>("cdnTest") {
    description = "Runs Snapshot CDN sync tests"
    group = "verification"
    testClassesDirs = sourceSets["cdnTest"].output.classesDirs
    classpath = sourceSets["cdnTest"].runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(agentTest, cdnTest)
}

tasks.register<JavaExec>("runAgent") {
    group = "application"
    description = "Run host agent from IDEA"
    classpath = sourceSets["agent"].runtimeClasspath
    mainClass.set("rpcnode.toolkit.agent.presentation.http.AgentMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = layout.projectDirectory.asFile
    val dataDir = layout.projectDirectory.dir("database")
    val agentState = dataDir.dir("agent").asFile
    val tokenFile = dataDir.file("agent.token").asFile
    environment("RPCNODE_DEV", "1")
    environment("AGENT_LISTEN", "0.0.0.0")
    environment("AGENT_TOKEN_FILE", tokenFile.absolutePath)
    environment("AGENT_CONFIG_DIR", agentState.absolutePath)
    environment("AGENT_CACHE_DIR", agentState.absolutePath)
    environment("AGENT_LOG_DIR", agentState.absolutePath)
    environment("AGENT_RANGE_FILE", dataDir.file("rpcnode-agent.ports").asFile.absolutePath)
    environment("AGENT_SYSCTL_CONF", dataDir.file("99-rpcnode-agent-ports.conf").asFile.absolutePath)
    doFirst {
        tokenFile.parentFile.mkdirs()
        agentState.mkdirs()
        if (!tokenFile.isFile || tokenFile.readText().isBlank())
        {
            tokenFile.writeText("local-dev-agent\n")
        }
        val listen = environment["AGENT_LISTEN"] as? String ?: "0.0.0.0"
        val port = (environment["AGENT_PORT"] as? String)?.toIntOrNull() ?: 48990
        val busy = try
        {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(listen, port))
            }
            null
        }
        catch (_: Exception)
        {
            val who = runCatching {
                val proc = ProcessBuilder("ss", "-lptn", "sport = :$port")
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                Regex("""users:\(\("([^"]+)",pid=(\d+)""").find(text)
                    ?.let { "${it.groupValues[1]} (pid ${it.groupValues[2]})" }
            }.getOrNull()
            if (who != null) "already in use by $who" else "port in use"
        }
        if (busy != null)
        {
            throw GradleException(
                "cannot bind $listen:$port — $busy. Stop that process or: sudo systemctl stop rpcnode-agent",
            )
        }
    }
}

tasks.register<ShadowJar>("agentFatJar") {
    group = "build"
    description = "Fat JAR for the host agent (rpcnode-agent.jar)"
    from(sourceSets["agent"].output)
    // Chain host adapters live under main `chains/<id>` — ship them with the agent JAR.
    from(sourceSets["main"].output) {
        include("rpcnode/toolkit/chains/**")
        include("rpcnode/toolkit/nodes/application/start/**")
        include("rpcnode/toolkit/nodes/application/config/**")
        include("rpcnode/toolkit/nodes/infrastructure/host/**")
        include("rpcnode/toolkit/catalog/domain/**")
        // Bsc/Base SnapshotResolver companions are referenced from the agent download path.
        include("rpcnode/toolkit/networks/application/snapshot/SnapshotResolver*")
        include("rpcnode/toolkit/networks/domain/model/SnapshotArchive*")
        // Outbound ktor-client helper used by chain height probes.
        include("rpcnode/toolkit/shared/infrastructure/http/**")
        include("rpcnode/toolkit/shared/infrastructure/log/**")
        // Per-network resources (`chains/<id>/network.yml`, clients.yml, *.tmpl).
        include("chains/**")
        include("rpcnode/toolkit/shared/infrastructure/classpath/**")
    }
    configurations.set(listOf(project.configurations["agentRuntimeClasspath"]))
    archiveClassifier.set("")
    archiveFileName.set("rpcnode-agent.jar")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "rpcnode.toolkit.agent.presentation.http.AgentMainKt"
    }
}

tasks.register<JavaExec>("runCdn") {
    group = "application"
    description = "Run Snapshot CDN sync from IDEA (local targets, no panel)"
    classpath = sourceSets["cdn"].runtimeClasspath
    mainClass.set("rpcnode.toolkit.cdn.presentation.CdnMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = layout.projectDirectory.asFile
    environment("RPCNODE_DEV", "1")
    environment("SNAPSHOT_CDN_DIR", layout.projectDirectory.asFile.absolutePath)
    environment("CDN_TARGETS_FILE", layout.projectDirectory.file("rpcnode-cdn.targets.json").asFile.absolutePath)
    environment("CDN_POLL_SEC", "30")
    environment("CDN_DOWNLOAD_JOBS", "4")
}

tasks.register<ShadowJar>("cdnFatJar") {
    group = "build"
    description = "Fat JAR for Snapshot CDN sync (rpcnode-cdn.jar)"
    from(sourceSets["cdn"].output)
    configurations.set(listOf(project.configurations["cdnRuntimeClasspath"]))
    archiveClassifier.set("")
    archiveFileName.set("rpcnode-cdn.jar")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "rpcnode.toolkit.cdn.presentation.CdnMainKt"
    }
}

ktor {
    fatJar {
        archiveFileName.set("rpcnode-server.jar")
    }
}
