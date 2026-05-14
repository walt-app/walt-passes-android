# ProGuard/R8 rules contributed by passes-core to any minified consumer build.
#
# passes-core is a plain Kotlin/JVM java-library, so it has no Android
# consumerProguardFiles DSL. R8/AGP instead auto-applies rule files found at
# META-INF/proguard/*.pro inside dependency JARs on the consumer classpath;
# this file is that channel.
#
# AppleTrustAnchors loads the bundled Apple .cer trust anchors by ABSOLUTE
# classpath name (see AppleTrustAnchors.kt), so the loader itself is already
# robust to class repackaging. This -keep is belt-and-suspenders: pinning the
# loader's class and package keeps stack traces / crash reports legible, guards
# against a future package-relative regression, and documents in-tree that this
# class is trust-claim-bearing and must not be silently shaded away.
-keep class is.walt.passes.core.internal.AppleTrustAnchors { *; }

# Intentionally NOT included:
#  * -keepdirectories — only needed when code enumerates a resource *directory*
#    via getResources(); AppleTrustAnchors looks up each .cer by exact name.
#  * -adaptresourcefilenames / -adaptresourcefilecontents — opt-in; absent them
#    R8 leaves java resources at their original paths, which is exactly what the
#    absolute lookup depends on.
