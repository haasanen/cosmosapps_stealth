# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# okhttp's Conscrypt/BouncyCastle/OpenJSSE TLS platforms are optional at runtime
# (okhttp detects their absence via reflection). R8 >= AGP 8 flags the missing
# classes as a build error; these are the rules R8 itself generates.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# Moshi's AdapterMethodsFactory validates @FromJson/@ToJson parameter types via
# reflection and requires the JsonAdapter<T> parameter to still carry its generic
# signature (a ParameterizedType), otherwise it throws "Unexpected signature for
# ..." at first adapter use (square/moshi#1663). R8 full mode (AGP 8 default)
# strips those signatures and this keep rule does NOT restore them — the fix is
# android.enableR8.fullMode=false in gradle.properties (verified: generic
# signature string count 453 with full mode on vs 2161 off, matching the last
# working build). This keep rule is kept as defense-in-depth.
-keep,allowobfuscation,allowshrinking class com.squareup.moshi.JsonAdapter
