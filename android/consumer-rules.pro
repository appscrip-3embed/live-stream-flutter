# Release R8 renames or strips members that ExternalVideoTrackManager calls
# by name (FlutterWebRTCPlugin.sharedSingleton, methodCallHandler, LocalVideoTrack).
# When that lookup fails, DeepAR initializes and is immediately destroyed.
# Debug builds do not run R8, so the same code works there.

-keep class com.cloudwebrtc.webrtc.** { *; }
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }

# DeepAR's native layer looks listener methods up by name.
-keep class ai.deepar.ar.** { *; }
-keep class com.deepar.flutter_deepar.** { *; }
-keepclassmembers class * implements ai.deepar.ar.AREventListener { *; }

-keep class com.example.appscrip_live_stream_component.** { *; }
