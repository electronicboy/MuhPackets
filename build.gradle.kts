import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
  `java-library`
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
  id("xyz.jpenilla.run-paper") version "3.1.0" // Adds runServer task for testing
  id("de.eldoria.plugin-yml.bukkit") version "0.9.0" // Generates plugin.yml
}

group = "pw.valaria"
version = "1.1.0-SNAPSHOT"
description = "Logs inbound packets per-player to disk"

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
  mavenCentral()
}

dependencies {
  // Compiled against the OLDEST supported Paper, deliberately. Paper 26.x ships Java 25 class
  // files, so building against it would force Java 25 bytecode that will not load on 1.21.x.
  // Building against 1.21.4 emits Java 21 bytecode, and every symbol this plugin touches is
  // unchanged all the way up to 26.2 (verified with jdeps against a real 26.2 server jar).
  paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
}

tasks {
  compileJava {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(21)
  }
  javadoc {
    options.encoding = Charsets.UTF_8.name()
  }
  processResources {
    filteringCharset = Charsets.UTF_8.name()
  }
}

bukkit {
  load = BukkitPluginDescription.PluginLoadOrder.STARTUP
  main = "pw.valaria.muhpackets.MuhPackets"
  apiVersion = "1.21"
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
