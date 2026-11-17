plugins {
    application
}

group = "gallery"
version = "2.0"

java {
    // no dependencies; runs on any JDK 17+
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

application {
    mainClass = "gallery.Main"
}

tasks.jar {
    archiveFileName = "CorelGallery.jar"
    manifest {
        attributes("Main-Class" to "gallery.Main")
    }
}
