import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
  `java-library`
  id("io.papermc.paperweight.userdev") version "1.5.4"
  id("xyz.jpenilla.run-paper") version "2.0.1" // Adds runServer and runMojangMappedServer tasks for testing
  id("net.minecrell.plugin-yml.bukkit") version "0.5.3" // Generates plugin.yml
  id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.papermc.paperweight"
version = "1.0.0-SNAPSHOT"
description = "Test plugin for paperweight-userdev"

java {
  // Configure the java toolchain. This allows gradle to auto-provision JDK 17 on systems that only have JDK 8 installed for example.
  toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
  maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
  paperweight.paperDevBundle("1.19.4-R0.1-SNAPSHOT")
  //implementation("xyz.jpenilla", "reflection-remapper", "0.1.0-SNAPSHOT")

  // paperweight.foliaDevBundle("1.19.4-R0.1-SNAPSHOT")
  // paperweight.devBundle("com.example.paperfork", "1.19.4-R0.1-SNAPSHOT")
}

tasks {
  // Configure reobfJar to run when invoking the build task
  assemble {
    dependsOn(reobfJar)
  }

  compileJava {
    options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything

    // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
    // See https://openjdk.java.net/jeps/247 for more information.
    options.release.set(17)
  }
  javadoc {
    options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
  }
  processResources {
    filteringCharset = Charsets.UTF_8.name() // We want UTF-8 for everything
  }

  /*
  reobfJar {
    // This is an example of how you might change the output location for reobfJar. It's recommended not to do this
    // for a variety of reasons, however it's asked frequently enough that an example of how to do it is included here.
    outputJar.set(layout.buildDirectory.file("libs/PaperweightTestPlugin-${project.version}.jar"))
  }
   */
}

// Configure plugin.yml generation
bukkit {
  load = BukkitPluginDescription.PluginLoadOrder.STARTUP
  main = "pw.valaria.muhpackets.MuhPackets"
  apiVersion = "1.19"
  authors = listOf("electronicboy")
  commands {
    register("muhpackets") {
      permission = "muhpackets.muhpackets"
    }
  }

  permissions {
    register("muhpackets.muhpackets") {
      default = BukkitPluginDescription.Permission.Default.OP
    }
  }
}
