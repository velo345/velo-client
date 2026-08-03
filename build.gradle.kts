plugins {
	// Applies the correct fabric-loom variant for the active node's
	// Minecraft version (Yarn-mapped pre-26.1, Mojang-mapped 26.1+).
	id("dev.kikugie.loom-back-compat")
	id("maven-publish")
}

// DO NOT set group here - loom-back-compat needs it set before its own
// plugin application resolves per-node dependencies.
group = property("mod.group") as String
version = "${property("mod.version")}+${sc.current.version}"

base {
	archivesName.set(property("mod.id") as String)
}

// 26.1 requires Java 25; everything else this mod supports stays on 21.
val requiredJava: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

repositories {
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.
}

loom {
	splitEnvironmentSourceSets()

	mods {
		create("velo-client") {
			sourceSet(sourceSets["main"])
			sourceSet(sourceSets["client"])
		}
	}
}

fabricApi {
	configureDataGeneration()
}

tasks.named<JavaExec>("runClient") {
	// The dev-run JVM was auto-detecting AWT as headless (no DISPLAY visible
	// at the moment GraphicsEnvironment first got touched, likely from how
	// the process gets launched/backgrounded during testing), which made
	// every Swing-based native file picker (the cape PNG import dialog)
	// throw HeadlessException instead of opening. Headless auto-detection
	// only runs once and can't be un-cached later, so it has to be forced
	// off up front instead.
	jvmArgs("-Djava.awt.headless=false")
}

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")
	// Yarn has no published mapping builds for 26.1/26.2 at all (Mojang's
	// switch to CalVer versioning shipped alongside dropping community
	// Yarn support in favour of their own official mappings) - everything
	// this mod already targets (1.21.11) keeps using the proven Yarn build
	// it shipped with before this multi-version migration.
	if (sc.current.parsed < "26.1") {
		mappings("net.fabricmc:yarn:${property("deps.yarn_mappings")}:v2")
	} else {
		loomx.applyMojangMappings()
	}
	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
	val modVersion = project.version.toString()
	// fabric.mod.json's own "minecraft" dependency used to be hardcoded to
	// "~1.21.11" - harmless when this was a single-version project, but it
	// made Fabric Loader flatly refuse to load the mod on 26.1/26.2 ("only
	// the wrong version is present"), discovered by actually launching the
	// 26.1 dev client rather than by compiling (fabric.mod.json isn't
	// Java, so javac can't catch this class of bug).
	val minecraftDependency = "~${sc.current.version}"
	val javaVersion = requiredJava.majorVersion
	inputs.property("version", modVersion)
	inputs.property("minecraftDependency", minecraftDependency)
	inputs.property("javaVersion", javaVersion)

	filesMatching("fabric.mod.json") {
		expand("version" to modVersion, "minecraft_dependency" to minecraftDependency, "java_version" to javaVersion)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(requiredJava.majorVersion.toInt())
	// Raised while porting to 26.x - javac's default 100-error cap was
	// cutting off the real error list mid-file during the mechanical
	// Yarn->Mojmap rename pass, hiding how many issues actually remained.
	options.compilerArgs.add("-Xmaxerrs")
	options.compilerArgs.add("2000")
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = requiredJava
	targetCompatibility = requiredJava
}

tasks.named<Jar>("jar") {
	val archivesName = base.archivesName.get()
	// Each version node's projectDir is versions/<version>/, not the repo
	// root where LICENSE actually lives - a bare "LICENSE" here silently
	// resolved to nothing and dropped the file from the jar.
	val licenseFile = rootProject.file("LICENSE")
	from(licenseFile) {
		rename { "${it}_${archivesName}" }
	}
}
