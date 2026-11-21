// Copy of OsmAnd's AIDL API module (net.osmand.aidlapi) — see NOTICE.md. Only used to call
// an installed OsmAnd through its AIDL service; package names must stay unchanged.
plugins {
    id("com.android.library")
}

android {
    namespace = "net.osmand.aidlapi"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            aidl.srcDirs("src")
            java.srcDirs("src")
            res.srcDirs("res")
        }
    }
    buildFeatures { aidl = true }
    lint { abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.1")
}
