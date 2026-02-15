dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Cloudstream extension for SenpaiStream - Films, Séries et Animés en français"
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

    requiresResources = false
    language = "fr"

    // Icon URL
    iconUrl = "https://senpai-stream.hair/favicon/favicon-32x32.png"
}