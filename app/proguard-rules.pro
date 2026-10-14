# Keep line numbers for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Keep the accessibility service entry point.
-keep class com.blockblast.solver.automation.TapAccessibilityService { *; }
