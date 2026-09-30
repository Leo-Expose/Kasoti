# KASOTI — R8 rules for the release build.
#
# Every rule here is a *defect* rule: something whose absence is silent. R8 cannot see that a
# TFLite model reaches its own entry points by name, and the symptom of a missing keep is a face
# model that works in debug and silently returns no detection in release — which, with the
# quality gate in front of it, means the face layer goes ABSENT and no genuine case can ever
# reach GREEN. That is a shipping-blocking failure with no stack trace, so the keeps are here.

# --- TFLite ------------------------------------------------------------------------------
# The interpreter's JNI layer is reached from native code by name. Renaming it produces an
# UnsatisfiedLinkError only on the release path.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.interpreter.** { *; }
-dontwarn org.tensorflow.lite.**

# The generated TFLite `Support` classes the model wrapper references by name.
-keep class dev.kasoti.android.platform.** { *; }

# --- ML Kit -------------------------------------------------------------------------------
# ML Kit's own consumer rules ship with the artifact; these cover the two call sites that go
# through reflection internally.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.android.gms.**

# --- :core -------------------------------------------------------------------------------
# `FusionEngine` and friends are ordinary Kotlin with no reflection, so they need no keep. The
# enum `FindingCode` is resolved by name in the scrubber, so its *constants* must survive.
-keepclassmembers enum dev.kasoti.fusion.FindingCode { *; }
-keepclassmembers enum dev.kasoti.fusion.Verdict { *; }
-keepclassmembers enum dev.kasoti.i18n.Language { *; }

# --- diagnostics --------------------------------------------------------------------------
# Line numbers are the only thing that makes a release stack trace usable on a post machine, and
# the source file name is what makes it greppable against a git SHA.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- PII ----------------------------------------------------------------------------------
# A release build must not ship a logger that could be talked into writing an identity field.
# The app's own sink is the `KASOTI` tag in `LogcatFieldLog`; this is belt and braces.
# (The real protection is that `FieldLog` has no overload taking a String that could be a name —
# see field/FieldLog.kt. There is no string to strip because no string can get there.)
