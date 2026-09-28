# Consumer ProGuard/R8 rules shipped with this module.
# Morsecode modules are obfuscated only through the :app release build; nothing
# here relies on reflection except the annotations listed explicitly.

# Kotlin coroutines / serialization keep their generated serializers reachable
# through the Kotlin metadata that R8 already preserves.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
