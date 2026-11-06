# Experimental NeoForge 1.20.1 port

Client-only development build, connects to MineFeed's dev API. Requires Minecraft 1.20.1, NeoForge 47.1.106 and Java 17+. Do not install another MineFeed jar at the same time.

Build with JDK 21 (compiled class files target Java 17):

```sh
CI=true ./neoforge-1.20.1/gradlew -p neoforge-1.20.1 build
```

ModDevGradle compiles against NeoForge 1.20.1 and outputs the SRG-reobfuscated jar including a Mixin refmap in `build/libs/`. The packaged jar was launched with official NeoForge 47.1.106 in an isolated client: a world loaded, J opened the welcome screen, and the inventory button opened it too. Sign-in, feed, posting, images, notifications, death and advancement flows require further testing. Config is registered through NeoForge's TOML configuration; there is no custom in-game configuration screen in this port.
