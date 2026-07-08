package org.quartzlang.embed.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Makes a class an opaque {@code @handle} type in Quartz: code holds a reference it cannot forge or look inside. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface QuartzHandle {
    /** The Quartz type name; the class's simple name when empty. */
    String name() default "";
}
