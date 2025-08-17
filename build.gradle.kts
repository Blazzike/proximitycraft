import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "2.2.10"
  kotlin("plugin.serialization") version "2.2.10"
  id("fabric-loom") version "1.11-SNAPSHOT"
  id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
  archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21
java {
  toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
  // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
  // if it is present.
  // If you remove this line, sources will not be generated.
  withSourcesJar()
}

loom {
  splitEnvironmentSourceSets()

  mods {
    register("proxmitycraft") {
      sourceSet("main")
      sourceSet("client")
    }
  }
}

sourceSets {
    val client by getting {
        kotlin.srcDirs("src/client/kotlin")
    }
}

fabricApi {
  configureDataGeneration {
    client = true
  }
}

repositories {
  // Add repositories to retrieve artifacts from in here.
  // You should only use this when depending on other mods because
  // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
  // See https://docs.gradle.org/current/userguide/declaring_repositories.html
  // for more information about repositories.
}

dependencies {
  // To change the versions see the gradle.properties file
  minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
  mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
  modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
  modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

  modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
  
  // Ktor dependencies - using 1.x version compatible with Fabric environment
  implementation("io.ktor:ktor-server-core:1.6.8")
  implementation("io.ktor:ktor-server-netty:1.6.8")
  implementation("io.ktor:ktor-websockets:1.6.8")
  implementation("io.ktor:ktor-serialization:1.6.8")
  
  // Kotlinx serialization
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
}

tasks.processResources {
  inputs.property("version", project.version)
  inputs.property("minecraft_version", project.property("minecraft_version"))
  inputs.property("loader_version", project.property("loader_version"))
  filteringCharset = "UTF-8"

  filesMatching("fabric.mod.json") {
    expand(
      "version" to project.version,
      "minecraft_version" to project.property("minecraft_version"),
      "loader_version" to project.property("loader_version"),
      "kotlin_loader_version" to project.property("kotlin_loader_version")
    )
  }
}

tasks.withType<JavaCompile>().configureEach {
  // ensure that the encoding is set to UTF-8, no matter what the system default is
  // this fixes some edge cases with special characters not displaying correctly
  // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
  // If Javadoc is generated, this must be specified in that task too.
  options.encoding = "UTF-8"
  options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
  from("LICENSE") {
    rename { "${it}_${project.base.archivesName}" }
  }
}

// Custom tasks for running multiple clients
tasks.register("runTwoClients") {
  group = "fabric"
  description = "Runs two clients with usernames Player1 and Player2 in parallel"

  dependsOn("classes")

  doLast {
    println("Starting two clients in parallel...")
    println("Player1 will connect to localhost:25565")
    println("Player2 will connect to localhost:25565")
    println("Press Ctrl+C to stop both clients")

    val client1Process = ProcessBuilder()
      .command("./gradlew", "runClient1")
      .directory(project.rootDir)
      .inheritIO()
      .start()

    val client2Process = ProcessBuilder()
      .command("./gradlew", "runClient2")
      .directory(project.rootDir)
      .inheritIO()
      .start()

    // Add shutdown hook to ensure processes are killed when task exits
    val shutdownHook = Thread {
      println("\nShutting down clients...")
      client1Process.destroyForcibly()
      client2Process.destroyForcibly()
      println("Clients stopped.")
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    try {
      // Wait for both processes to complete
      val exitCode1 = client1Process.waitFor()
      val exitCode2 = client2Process.waitFor()

      println("Client1 exited with code: $exitCode1")
      println("Client2 exited with code: $exitCode2")
    } catch (e: InterruptedException) {
      println("Task interrupted, stopping clients...")
      client1Process.destroyForcibly()
      client2Process.destroyForcibly()
    } finally {
      // Remove shutdown hook since we're handling cleanup manually
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
      } catch (e: IllegalStateException) {
        // Hook was already executed, ignore
      }
    }
  }
}

tasks.register<JavaExec>("runClient1") {
  group = "fabric"
  description = "Run client with username Player1"

  dependsOn("classes")
  classpath = sourceSets["main"].runtimeClasspath + sourceSets["client"].runtimeClasspath
  mainClass.set("net.fabricmc.loader.launch.knot.KnotClient")

  workingDir = file("run1")
  doFirst {
    workingDir.mkdirs()
  }

  jvmArgs(
    "-XstartOnFirstThread",
    "-Dfabric.development=true",
    "-Dfabric.remapClasspathFile=${tasks.named("remapJar").get().outputs.files.asPath}",
    "-Dlog4j.configurationFile=${project.file("src/main/resources/log4j2.xml")}",
    "-Dmixin.debug.export=true"
  )

  args(
    "--gameDir", workingDir.absolutePath,
    "--username", "Player1",
    "--uuid", "00000000-0000-0000-0000-000000000001"
  )
}

tasks.register<JavaExec>("runClient2") {
  group = "fabric"
  description = "Run client with username Player2"

  dependsOn("classes")
  classpath = sourceSets["main"].runtimeClasspath + sourceSets["client"].runtimeClasspath
  mainClass.set("net.fabricmc.loader.launch.knot.KnotClient")

  workingDir = file("run2")
  doFirst {
    workingDir.mkdirs()
  }

  jvmArgs(
    "-XstartOnFirstThread",
    "-Dfabric.development=true",
    "-Dfabric.remapClasspathFile=${tasks.named("remapJar").get().outputs.files.asPath}",
    "-Dlog4j.configurationFile=${project.file("src/main/resources/log4j2.xml")}",
    "-Dmixin.debug.export=true"
  )

  args(
    "--gameDir", workingDir.absolutePath,
    "--username", "Player2",
    "--uuid", "00000000-0000-0000-0000-000000000002"
  )
}

// configure the maven publication
publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = project.property("archives_base_name") as String
      from(components["java"])
    }
  }

  // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
  repositories {
    // Add repositories to publish to here.
    // Notice: This block does NOT have the same function as the block in the top level.
    // The repositories here will be used for publishing your artifact, not for
    // retrieving dependencies.
  }
}
