# Third-party notices

NeoProCamX directly depends on the following major third-party projects. The shipped dependency graph and packaged license metadata must be regenerated and reviewed for every release.

| Project family | Use | License/terms |
| --- | --- | --- |
| AndroidX, CameraX, Media3, Material Components | Android UI, lifecycle, media, camera types | Apache License 2.0 |
| Kotlin and kotlinx.coroutines | Language/runtime and concurrency | Apache License 2.0 |
| OkHttp | HTTPS client infrastructure | Apache License 2.0 |
| Glide | Image loading | BSD-style license |
| MediaPipe and LiteRT | On-device vision/inference | Apache License 2.0 |
| Google ML Kit | On-device/mobile ML features | Google APIs terms; component-specific notices apply |
| Google Play Integrity | App-integrity checks | Google Play/Google APIs terms |
| JUnit | Unit testing (not packaged for production) | Eclipse Public License 1.0 |
| LZ4 1.10.0 | Bit-exact NLC2 block compression/decompression | BSD 2-Clause; license included at `app/src/main/cpp/third_party/lz4/LICENSE` |

Exact versions are pinned in `gradle/libs.versions.toml`. Transitive components can add notices not listed above. Before distribution, archive the resolved release dependency graph, inspect every packaged `META-INF/LICENSE*` and `META-INF/NOTICE*` entry, and provide complete license texts in the store/support distribution channel.

NeoProCamX's own source and assets remain proprietary unless a file states otherwise.
