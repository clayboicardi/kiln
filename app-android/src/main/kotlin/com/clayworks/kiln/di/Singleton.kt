// Singleton — kotlin-inject scope annotation. Per kotlin-inject 0.9.x: a
// custom @Scope annotation cached at the component level. @Provides functions
// and @Inject classes annotated with @Singleton are instantiated once per
// component (the AndroidAppGraph itself is process-lived).

package com.clayworks.kiln.di

import me.tatarka.inject.annotations.Scope

@Scope
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
)
annotation class Singleton
