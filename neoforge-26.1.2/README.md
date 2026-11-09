# NeoForge 26.1.2 port

Client-only build; connects to the MineFeed V2 production API. Requires Minecraft 26.1.2, NeoForge 26.1.2.126 or newer for 26.1.2, and Java 25+. Do not install another MineFeed jar at the same time.

Build with JDK 25:

```sh
CI=true ./neoforge-26.1.2/gradlew -p neoforge-26.1.2 build
```

The binary-only mode keeps the build lightweight. The jar is in `build/libs/`. The packaged jar was launched in a NeoForge development runtime: a world loaded, J opened the welcome screen and the inventory button appeared. When changing this port, test installation and the sign-in, feed, posting, image and notification flows in an ordinary launcher. Multiline input uses the vanilla 26.1 widget, so its appearance can differ from older versions. Settings use `config/feed-neoforge-26.1.2.json`; there is no in-game configuration screen.
