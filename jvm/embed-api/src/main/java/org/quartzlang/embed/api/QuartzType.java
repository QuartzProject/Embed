package org.quartzlang.embed.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a record, Kotlin data class, enum or sealed type in the host interface even when no {@link QuartzFunction}
 * names it, for values the host passes in by itself, such as the events it hands to {@code @on} functions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface QuartzType {}
