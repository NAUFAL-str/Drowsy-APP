# ───────────────────────────────────────────────────
# Disable all code-shrinking, obfuscation, and optimization
# ───────────────────────────────────────────────────

-dontshrink
-dontobfuscate
-dontoptimize

# (You could also keep a few required rules, but in effect
#  this makes R8 a “no-op” that simply packages everything
#  without renaming or removing.)

# Keep everything under MediaPipe so nothing gets stripped/renamed:
-keep class com.google.mediapipe.** { *; }

# Keep everything under TensorFlow Lite so .tflite still works:
-keep class org.tensorflow.lite.** { *; }

# Keep any native methods (JNI) so media pipe’s native .so loading still works:
-keep class * {
    native <methods>;
}

# Keep any AndroidX CameraX / Lifecycle classes (optional):
-keep class androidx.camera.** { *; }
-keep class androidx.lifecycle.** { *; }

-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.Filer
-dontwarn javax.annotation.processing.Messager
-dontwarn javax.annotation.processing.ProcessingEnvironment
-dontwarn javax.annotation.processing.Processor
-dontwarn javax.annotation.processing.RoundEnvironment
-dontwarn javax.annotation.processing.SupportedAnnotationTypes
-dontwarn javax.annotation.processing.SupportedOptions
-dontwarn javax.lang.model.AnnotatedConstruct
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.AnnotationMirror
-dontwarn javax.lang.model.element.AnnotationValue
-dontwarn javax.lang.model.element.AnnotationValueVisitor
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.ElementVisitor
-dontwarn javax.lang.model.element.ExecutableElement
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.element.Name
-dontwarn javax.lang.model.element.NestingKind
-dontwarn javax.lang.model.element.PackageElement
-dontwarn javax.lang.model.element.QualifiedNameable
-dontwarn javax.lang.model.element.TypeElement
-dontwarn javax.lang.model.element.TypeParameterElement
-dontwarn javax.lang.model.element.VariableElement
-dontwarn javax.lang.model.type.ArrayType
-dontwarn javax.lang.model.type.DeclaredType
-dontwarn javax.lang.model.type.ErrorType
-dontwarn javax.lang.model.type.ExecutableType
-dontwarn javax.lang.model.type.IntersectionType
-dontwarn javax.lang.model.type.NoType
-dontwarn javax.lang.model.type.NullType
-dontwarn javax.lang.model.type.PrimitiveType
-dontwarn javax.lang.model.type.TypeKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVariable
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.type.WildcardType
-dontwarn javax.lang.model.util.AbstractElementVisitor8
-dontwarn javax.lang.model.util.ElementFilter
-dontwarn javax.lang.model.util.Elements
-dontwarn javax.lang.model.util.SimpleAnnotationValueVisitor8
-dontwarn javax.lang.model.util.SimpleElementVisitor8
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.lang.model.util.Types
-dontwarn javax.tools.Diagnostic$Kind
-dontwarn javax.tools.JavaFileObject$Kind
-dontwarn javax.tools.JavaFileObject
-dontwarn javax.tools.SimpleJavaFileObject