package paradise3.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Producer-only opt-in for generating an ordinary precompiled {@link ExpansionHandler} adapter.
 *
 * <p>The embedded producer compiler plugin consumes this source annotation, removes it from the
 * emitted marker declaration, and injects the existing runtime {@link expander} metadata. It does
 * not carry a handler name and does not make annotation constructor arguments available as JVM
 * runtime values.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface embeddedExpander {}
