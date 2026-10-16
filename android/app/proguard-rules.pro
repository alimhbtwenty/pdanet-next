# The Xray bridge (gomobile AAR) is called through JNI: keep its Java classes and the go runtime glue.
-keep class go.** { *; }
-keep class xraybridge.** { *; }
