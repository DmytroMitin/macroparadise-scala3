package paradise3.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata annotation for precompiled marker annotations.
 *
 * <p>Place {@code @expander("fully.qualified.HandlerClass")} on a marker annotation to let the
 * plugin discover which precompiled {@link ParadiseAnnotationExpander} class should handle that
 * marker. The handler class must still be compiled before use and reachable through the explicit
 * handler classpath.
 *
 * <p>This annotation is metadata only. It does not make the handler executable by itself, does not
 * support same-module source handlers, and is not Scala 2 {@code macroTransform} or Scala 3 {@code
 * MacroAnnotation.transform}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface expander {
  String value();
}
