# Fabric 1.20.1 port

This is the client-only Fabric port of MineFeed V2 to Minecraft 1.20.1. It uses the same production API endpoint as the shared client. Install Fabric Loader 0.18.6 or newer and Fabric API 0.92.12+1.20.1, and run Minecraft with Java 17 or newer. This build is not for NeoForge.

Build from the repository root with JDK 21:

```sh
./gradlew -p fabric-1.20.1 build
```

The remapped distributable is in `fabric-1.20.1/build/libs/`, and its class files target Java 17. The build copies shared 1.21.1 client and Fabric sources to `build/generated/shared` and applies checked 1.20.1 API compatibility transformations in `compat.gradle`. Those generated sources are not the source of truth. If shared code changes cause a pattern check to fail, update and retest the port before releasing it. Fabric settings are stored in `config/feed-fabric.json`; there is currently no in-game configuration screen.
