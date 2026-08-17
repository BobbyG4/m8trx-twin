rootProject.name = "m8trx-twin"

dependencyResolutionManagement {
    versionCatalogs {
        create("deps") {
            version("kotlin", "2.4.0")
            version("ktlint", "12.2.0")
            version("jnats", "2.25.3")
            version("jackson", "2.22.1")
            version("coroutines", "1.11.0")
            version("logback", "1.5.37")
            plugin("kotlinJvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
            plugin("ktlint", "org.jlleitschuh.gradle.ktlint").versionRef("ktlint")
        }
    }
}
