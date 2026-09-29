-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class app.baddhu.dms.** {
    *** Companion;
}
-keepclasseswithmembers class app.baddhu.dms.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.baddhu.dms.**$$serializer { *; }

-keepclassmembers class app.baddhu.dms.rules.Rules {
    <init>(...);
}
