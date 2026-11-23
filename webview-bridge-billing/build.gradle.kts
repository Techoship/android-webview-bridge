plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.techoship.webviewbridge.billing"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 26
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":webview-bridge"))
    api(libs.billing)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = providers.gradleProperty("bridge.group").get()
                artifactId = "webview-bridge-billing"
                // JitPack passes the git tag as VERSION.
                version = providers.environmentVariable("VERSION").orElse(providers.gradleProperty("bridge.version")).get()
            }
        }
    }
}
