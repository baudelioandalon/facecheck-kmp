# kotlinx.serialization generates a synthetic $serializer for every @Serializable
# class and reaches it reflectively from the generated Companion. R8 in a
# consumer app cannot see that edge and strips it, which turns every API
# response into a SerializationException at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.borealnetwork.facecheck.** {
    *** Companion;
}
-keepclasseswithmembers class com.borealnetwork.facecheck.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.borealnetwork.facecheck.**$$serializer { *; }