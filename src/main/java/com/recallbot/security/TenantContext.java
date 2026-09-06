package com.recallbot.security;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Request-scoped tenant context holding the active groupId in a ThreadLocal.
 * Guarantees fail-safe cleanup via AutoCloseable to prevent thread-local leakage
 * across pooled threads.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_GROUP = new ThreadLocal<>();

    private TenantContext() {
        // Private constructor for utility class
    }

    /**
     * Sets the active group identifier for the current thread.
     *
     * @param groupId the database group identifier
     */
    public static void setGroupId(Long groupId) {
        if (groupId != null) {
            CURRENT_GROUP.set(groupId);
        } else {
            CURRENT_GROUP.remove();
        }
    }

    /**
     * Returns the active group identifier for the current thread.
     *
     * @return Optional containing current group ID, or empty if not set
     */
    public static Optional<Long> getGroupId() {
        return Optional.ofNullable(CURRENT_GROUP.get());
    }

    /**
     * Clears the active group identifier for the current thread.
     */
    public static void clear() {
        CURRENT_GROUP.remove();
    }

    /**
     * Scopes execution to the specified groupId using try-with-resources.
     * Restores the previous tenant state when closed.
     *
     * @param groupId the database group identifier to bind
     * @return an AutoCloseable tenant scope
     */
    public static TenantScope with(Long groupId) {
        Long previous = CURRENT_GROUP.get();
        setGroupId(groupId);
        return new TenantScope(previous);
    }

    /**
     * Executes a supplier function within the context of a given groupId.
     *
     * @param groupId  the group identifier
     * @param supplier the computation to execute
     * @param <T>      the return type
     * @return the result of the computation
     */
    public static <T> T execute(Long groupId, Supplier<T> supplier) {
        try (TenantScope ignored = with(groupId)) {
            return supplier.get();
        }
    }

    /**
     * Executes a runnable within the context of a given groupId.
     *
     * @param groupId  the group identifier
     * @param runnable the action to execute
     */
    public static void execute(Long groupId, Runnable runnable) {
        try (TenantScope ignored = with(groupId)) {
            runnable.run();
        }
    }

    /**
     * AutoCloseable scope that restores previous tenant on close.
     */
    public static final class TenantScope implements AutoCloseable {
        private final Long previousGroupId;

        private TenantScope(Long previousGroupId) {
            this.previousGroupId = previousGroupId;
        }

        @Override
        public void close() {
            if (previousGroupId != null) {
                CURRENT_GROUP.set(previousGroupId);
            } else {
                CURRENT_GROUP.remove();
            }
        }
    }
}
