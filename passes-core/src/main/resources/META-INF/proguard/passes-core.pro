# ProGuard/R8 rules contributed by passes-core to any minified consumer build.
#
# passes-core is a plain Kotlin/JVM java-library, so it has no Android
# consumerProguardFiles DSL. R8/AGP instead auto-applies rule files found at
# META-INF/proguard/*.pro inside dependency JARs on the consumer classpath;
# this file is that channel. passes-core owns these rules so each consumer
# does not have to rediscover them.

# --- Bundled Apple trust anchors -------------------------------------------
#
# AppleTrustAnchors loads the bundled Apple .cer trust anchors by ABSOLUTE
# classpath name (see AppleTrustAnchors.kt), so the loader itself is already
# robust to class repackaging. This -keep is belt-and-suspenders: pinning the
# loader's class and package keeps stack traces / crash reports legible, guards
# against a future package-relative regression, and documents in-tree that this
# class is trust-claim-bearing and must not be silently shaded away.
-keep class is.walt.passes.core.internal.AppleTrustAnchors { *; }

# --- BouncyCastle JCE provider ----------------------------------------------
#
# passes-core's signature verifier holds its own BouncyCastleProvider instance
# (bcprov-jdk18on, bcpkix-jdk18on) and passes it directly to the BC CMS / cert
# builders. BouncyCastleProvider's constructor registers its JCA algorithms
# REFLECTIVELY: it does Class.forName("<pkg>.<Alg>$Mappings") for each algorithm
# family, and each $Mappings class in turn registers the actual SPI
# implementations by string class name. R8 sees no direct references to any of
# it, so without these rules it shrinks the $Mappings + SPI classes away and
# renames whatever is left. The provider then constructs but registers almost
# nothing -- Signature.getInstance("SHA256withRSA", BC_PROVIDER) and
# CertPathBuilder.getInstance("PKIX", BC_PROVIDER) throw inside verifySignature,
# the outer runCatching absorbs it, and EVERY Apple-signed pkpass surfaces as
# Failed(SignatureCryptoFailure) in a minified release build.
#
# Keep the two provider packages whole, names included (the lookups are by
# literal string name, so renaming breaks them just as removal does). This is
# the established minimal-practical set for bcprov under R8: it does NOT keep
# org.bouncycastle.{asn1,crypto,math,cms,cert,operator}.** -- those are either
# referenced directly from passes-core code or transitively from the kept
# provider classes, so R8's normal reachability retains exactly the parts in use.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# Intentionally NOT included:
#  * -keepdirectories — only needed when code enumerates a resource *directory*
#    via getResources(); AppleTrustAnchors looks up each .cer by exact name.
#  * -adaptresourcefilenames / -adaptresourcefilecontents — opt-in; absent them
#    R8 leaves java resources at their original paths, which is exactly what the
#    absolute lookup depends on.
#  * a blanket -keep class org.bouncycastle.** { *; } — strictly larger than the
#    provider-package keep above and would defeat shrinking the unused crypto.
