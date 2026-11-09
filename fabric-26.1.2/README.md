# Fabric 26.1.2 port

This client-only port connects to the MineFeed V2 production API. Requires Minecraft 26.1.2, Fabric Loader 0.20.1 or newer, Fabric API 0.155.3+26.1.2, and Java 25 or newer. Do not install it alongside another MineFeed jar.

Build with JDK 25:

```sh
./fabric-26.1.2/gradlew -p fabric-26.1.2 build
```

The jar is written to `fabric-26.1.2/build/libs/`. The source in this directory is a separate snapshot of the client for this port. A development client was launched and entered a single-player world; the inventory button and account welcome screen were visually checked. Test authentication, feed loading, posting and installation in a normal launcher when changing this port. Minecraft 26.1.2 changed GUI APIs; the multiline input uses the new vanilla widget, so its styling may differ from earlier versions.
