# Fetcher

***Fetches vanilla/mod assets at runtime instead of including them in builds potentially violating licenses.***

Fetcher is a faster, stricter fork of CleanroomMC's AssetMover. The code, API, mod id (`assetmover`), asset folder and
`cleanroom.asset-mover.*` properties keep the AssetMover name, so mods written for AssetMover work unchanged.

### Features:

1. Downloading assets from Minecraft (Java and Bedrock), CurseForge, Modrinth, GitHub, Maven repositories and URLs.
2. Locating these in `.minecraft/assetmover` folder resource pack that is hidden in-game.
3. Dev environment compatibility.
4. Fast: many parallel downloads over reused connections, JSON fetched gzipped, every file hashed while it downloads.
5. Safe: see below.

### For Developers:

1. Add CleanroomMC's repository and query for AssetMover's maven entry:

```
repositories {
    maven {
        url 'https://maven.cleanroommc.com'
    }
}

dependencies {
    implementation 'com.cleanroommc:assetmover:3.0.0'
}
```

2. Register a listener on `MinecraftForge.EVENT_BUS` no later than `FMLConstructionEvent`, and queue assets through `MoveAssetEvent.Client`. Use `MoveAssetEvent.Server` for the dedicated server, which fires in pre-initialization, or `MoveAssetEvent` for both:

```java
@SubscribeEvent
public void onMoveAssets(MoveAssetEvent.Client event) {
    AssetMoverAPI api = event.getAssetMover();
    api.fromJava("1.20.1")
        .moveAs("assets/minecraft/textures/block/dirt.png", "assets/mymod/textures/block/dirt.png");
    api.fromModSite(ModSite.CURSEFORGE, "238222", "4593548")
        .move("assets/jei/lang/en_us.lang", "assets/jei/lang/de_de.lang");
}
```

### Timeouts

Each download gives up after 30 seconds without a connection or without data. Override it per source with a system property, in seconds:

| Property                                  | Source                                  |
|-------------------------------------------|-----------------------------------------|
| `cleanroom.asset-mover.java.timeout`      | `fromJava`                              |
| `cleanroom.asset-mover.bedrock.timeout`   | `fromBedrock`                           |
| `cleanroom.asset-mover.mod-site.timeout`  | `fromModSite`                           |
| `cleanroom.asset-mover.git.timeout`       | `fromGit`, `fromGitRelease`             |
| `cleanroom.asset-mover.maven.timeout`     | `fromMaven`, `fromMavenCentral`         |
| `cleanroom.asset-mover.url.timeout`       | `fromUrl`, `fromUrlFile`                |

Downloads run on `min(32, max(8, cores * 4))` threads, which `cleanroom.asset-mover.threads` overrides.

### Safety

- **https only.** Plain `http` and other schemes such as `file:` are refused, and so is a redirect that downgrades to them. `http://localhost` is exempt, and `-Dcleanroom.asset-mover.allow-insecure=true` permits any http.
- **Nothing escapes the pack.** A destination such as `../../mods/x.jar` or an absolute path is rejected with an `IllegalArgumentException`, as is a Minecraft version that is not a plain version id.
- **Never a half-written asset.** Files are written under a temporary name and moved into place only once complete and verified, so an interrupted launch cannot leave a truncated file that the next launch would skip as already present. Truncated downloads are detected and retried.
- **Verified before they land.** Mojang's assets are checked against Mojang's SHA-1, including the copies found in your launcher's folder. Requests with `verify(...)` move nothing out of a file that fails it. Without one, the SHA-256 of each archive is logged so it can be pinned.
- **Bounded.** A download over 1 GiB or a single extracted asset over 512 MiB is aborted, which `cleanroom.asset-mover.max-download` and `cleanroom.asset-mover.max-asset` (in MiB) override. Asset indexes are validated before their hashes are used in paths and urls.
- **Resilient.** Responses of 429 and 5xx, and dropped connections, are retried up to 3 times (`cleanroom.asset-mover.attempts`). Timeouts are not.
