# Experimental Fabric 26.1.2 port

This client-only port is a work in progress, not a production release. It connects to the MineFeed development API, not the production feed. Requires Minecraft 26.1.2, Fabric Loader 0.20.1 or newer, Fabric API 0.155.3+26.1.2, and Java 25 or newer. Do not install it alongside another MineFeed jar.

Build with JDK 25:

```sh
./fabric-26.1.2/gradlew -p fabric-26.1.2 build
```

The jar is written to `fabric-26.1.2/build/libs/`. The source in this directory is a separate snapshot of the client for this port. A development client was launched and entered a single-player world; the inventory button and account welcome screen were visually checked. Authentication, feed loading, posting, images, death and advancement drafts, and other features still need end-to-end testing, as does installation of the packaged jar in a normal launcher. Minecraft 26.1.2 changed GUI APIs; the multiline input uses the new vanilla widget, so its styling may differ from earlier versions.
