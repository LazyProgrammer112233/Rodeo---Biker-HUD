// Runs synthetic riders' speech through the real offline recogniser and the app's own voice
// logic (wake phrase, commands, SOS), and reports how often each does the right thing.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

sourceSets {
    main {
        kotlin {
            // The app's pure voice logic, compiled as is.
            srcDir("../../app/src/main/java")
            include(
                "com/rodeo/hud/voice/Phonetic.kt",
                "com/rodeo/hud/voice/Commands.kt",
                "com/rodeo/hud/voice/WakeMatcher.kt",
                "com/rodeo/hud/voice/VoiceBrain.kt",
                "com/rodeo/hud/voice/AudioFront.kt",
                "eval/**",
            )
        }
    }
}

dependencies {
    // Desktop build of the same recogniser the app uses on the phone.
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("org.json:json:20240303")
}

application { mainClass.set("eval.VoiceEvalKt") }
