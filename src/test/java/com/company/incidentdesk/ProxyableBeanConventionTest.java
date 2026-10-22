package com.company.incidentdesk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Spring proxies beans by generating a CGLIB subclass, so a {@code final} bean class that needs a
 * proxy fails at application startup with "Cannot subclass final class" rather than at compile
 * time. This test fails the build instead.
 *
 * <p>A class needs a proxy when it is a {@code @Repository} (wrapped for persistence exception
 * translation) or when it carries {@code @Transactional}, {@code @Async}, {@code @Cacheable}, or
 * {@code @Validated} on the class or any method.
 */
class ProxyableBeanConventionTest {
    private static final String BASE_PACKAGE = "com.company.incidentdesk";

    private static final List<Class<? extends Annotation>> PROXY_TRIGGERING_ANNOTATIONS =
            List.of(Transactional.class, Async.class, Cacheable.class, Validated.class);

    @Test
    void proxiedBeanClassesAreNotFinal() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> candidate : scanComponentClasses()) {
            if (!Modifier.isFinal(candidate.getModifiers())) {
                continue;
            }
            String reason = proxyReason(candidate);
            if (reason != null) {
                offenders.add(candidate.getName() + " is final but is proxied because " + reason);
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "Spring cannot create a CGLIB proxy for a final class. Remove `final` from:\n  "
                        + String.join("\n  ", offenders));
    }

    /** Returns why the class needs a proxy, or null when it does not need one. */
    private static String proxyReason(Class<?> type) {
        if (type.isAnnotationPresent(Repository.class)) {
            return "it is annotated @Repository (persistence exception translation)";
        }
        for (Class<? extends Annotation> annotation : PROXY_TRIGGERING_ANNOTATIONS) {
            if (type.isAnnotationPresent(annotation)) {
                return "it is annotated @" + annotation.getSimpleName();
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotation)) {
                    return "its method " + method.getName() + " is annotated @"
                            + annotation.getSimpleName();
                }
            }
        }
        return null;
    }

    /** Every Spring stereotype class under the application base package. */
    private static Set<Class<?>> scanComponentClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
                                    beanDefinition) {
                        // The default implementation rejects non-instantiable types; here we want
                        // every annotated class, independent of whether it is concrete.
                        return true;
                    }
                };
        scanner.addIncludeFilter(
                new AnnotationTypeFilter(org.springframework.stereotype.Component.class));

        Set<Class<?>> classes = new java.util.LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                classes.add(Class.forName(className, false, ProxyableBeanConventionTest.class
                        .getClassLoader()));
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                // A component whose optional dependencies are absent from the test classpath
                // cannot be proxied in this run either, so skipping it is safe.
            }
        }
        return classes;
    }
}
