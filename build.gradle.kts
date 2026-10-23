// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Folder build khusus di PC Windows pemilik; di Linux/CI memakai folder bawaan.
if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    layout.buildDirectory.set(file("C:/Users/Atta/.gradle-builds/anydrop/root"))
}
