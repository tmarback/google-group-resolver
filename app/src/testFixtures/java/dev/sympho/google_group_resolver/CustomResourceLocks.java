package dev.sympho.google_group_resolver;

/**
 * Resource locks shared by different test suites.
 */
public final class CustomResourceLocks {

    /** The global Reactor schedulers and factories. */
    public static final String SCHEDULERS = "reactor.schedulers";

    /** Do not instantiate. */
    private CustomResourceLocks() {}
    
}
