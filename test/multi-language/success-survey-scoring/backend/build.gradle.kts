plugins { application; kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
application { mainClass.set("gold.debug.samples.survey.MainKt") }
dependencyLocking { lockAllConfigurations(); lockMode.set(LockMode.STRICT) }
dependencies { implementation("com.google.code.gson:gson:2.14.0"); implementation("org.xerial:sqlite-jdbc:3.46.0.0") }
