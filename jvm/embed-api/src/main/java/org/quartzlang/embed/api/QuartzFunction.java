package org.quartzlang.embed.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Offers a method to Quartz code: the build declares it in the generated host interface and the runtime binds it.
 *
 * <p>A static method (a Kotlin top-level function or an {@code object} member) becomes a free function. An instance
 * method of a {@link QuartzHandle} class becomes a method of that handle, and so does a Kotlin extension function on
 * a handle class, or a static method marked {@link #method()} whose first parameter is a handle. Any other instance
 * method is called on the instance given to {@code QuartzEmbed.Builder#host(Object)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface QuartzFunction {
    /** The name Quartz code calls it by; the method's own name when empty. */
    String name() default "";

    /** Declares a handle method without arguments as {@code @property}: Quartz reads it as {@code p.health}. */
    boolean property() default false;

    /** Makes a static method whose first parameter is a handle a method of that handle, the parameter its self. */
    boolean method() default false;
}
