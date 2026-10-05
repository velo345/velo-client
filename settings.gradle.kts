pluginManagement {
	repositories {
		// Stonecutter + loom-back-compat checked in (gradle/vendor-maven): maven.kikugie.dev is often too slow
		// or unreachable from GitHub's build machines, which broke the release builds.
		maven(uri("gradle/vendor-maven")) {
			name = "Vendored plugins"
			// loom-back-compat is published with Gradle module metadata only (no POM).
			metadataSources {
				gradleMetadata()
				mavenPom()
			}
		}
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.7"
	// Bridges Yarn-mapped (pre-26.1) and Mojang-mapped (26.1+) Loom setups
	// behind one build script - see https://codeberg.org/KikuGie/loom-back-compat
	id("dev.kikugie.loom-back-compat") version "0.4.2"
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
	create(rootProject) {
		versions("1.21.11")
		version("26.1", "26.1")
		version("26.2", "26.2")
		// 1.21.11 is what's actually deployed to ~/.minecraft/mods/ right
		// now (see build-and-deploy.sh) - keep it the checked-in default so
		// a plain IDE open / non-stonecutter-aware tooling still lands on
		// the version that matters most.
		vcsVersion = "1.21.11"
	}
}

rootProject.name = "velo-client"

include("launcher")
include("server")
