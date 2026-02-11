dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Cloudstream extension for SenpaiStream"
    authors = listOf("Antigravity")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 3 // Beta-only

    tvTypes = listOf("Movie", "TvSeries", "Anime")

    requiresResources = true
    language = "fr"

    // Icon URL
    iconUrl = "https://senpai-stream.hair/favicon/apple-touch-icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}