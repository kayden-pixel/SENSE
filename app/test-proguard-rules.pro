# Error Prone's IncompatibleModifiers annotation is a compiler-only annotation.
# Its javax.lang.model enum is supplied by a JDK, not the Android runtime.
# This rule applies only to the instrumentation APK, never the application APK.
-dontwarn javax.lang.model.element.Modifier
