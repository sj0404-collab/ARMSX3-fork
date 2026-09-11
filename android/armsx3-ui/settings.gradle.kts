pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // No foojay-resolver-convention: nothing here targets a toolchain. Java
    // version comes from JAVA_HOME (CI pins it via actions/setup-java), so the
    // only thing this plugin ever did was add a Plugin-Portal network fetch that
    // killed CI when that end flaked (the last release build failed exactly on
    // "org.gradle.toolchains.foojay-resolver-convention:1.0.0 was not found").
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Discord Social SDK ships as a local .aar. Consumed as a dependency rather than unpacked
        // by hand: hand-staging silently dropped its consumer proguard rules (crash at boot), its
        // <queries> entry, and its androidx.browser dependency (sign-in completed and the result
        // was discarded). An .aar is its manifest and dependency graph too, not just a .so.
        // The SDK .aar itself is NOT in this repository: Discord's terms permit shipping it inside
        // a working application but not republishing the raw SDK, so private release CI supplies it
        // through DISCORD_SDK_DIR. app/libs stays on the list for any other local .aar and for
        // developers who keep their own copy there.
        val discordSdkDir = providers.environmentVariable("DISCORD_SDK_DIR").orNull
            ?: providers.gradleProperty("DISCORD_SDK_DIR").orNull
        flatDir { dirs(listOfNotNull("app/libs", discordSdkDir)) }
    }
}

rootProject.name = "ARMSX2"
include(":app")
 