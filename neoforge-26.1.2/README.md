# Experimental NeoForge 26.1.2 port

Client-only development build, connects to MineFeed's dev API. Requires Minecraft 26.1.2, NeoForge 26.1.2.126 or newer for 26.1.2, and Java 25+. Do not install another MineFeed jar at the same time.

Build with JDK 25:

```sh
CI=true ./neoforge-26.1.2/gradlew -p neoforge-26.1.2 build
```

The binary-only mode keeps the build lightweight. The jar is in `build/libs/`. The packaged jar was launched in a NeoForge development runtime: a world loaded, J opened the welcome screen and the inventory button appeared. Installation in an ordinary launcher and the sign-in, feed, posting, image, notification, death and advancement flows require further testing. Multiline input uses the vanilla 26.1 widget, so its appearance can differ from older versions. Settings use `config/feed-neoforge-26.1.2.json`; there is no in-game configuration screen.
