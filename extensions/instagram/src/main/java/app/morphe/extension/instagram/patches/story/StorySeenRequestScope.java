/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class StorySeenRequestScope {
    static final long PERMIT_TTL_MILLIS = 30_000L;
    private static final int MAX_PENDING_PERMITS = 32;
    private static final RequestRegistry REGISTRY = new RequestRegistry(
            () -> System.nanoTime() / 1_000_000L,
            PERMIT_TTL_MILLIS,
            MAX_PENDING_PERMITS,
            StorySeenState::remember
    );

    private StorySeenRequestScope() {
    }

    public static boolean register(Object request, StorySeenKey storyKey, Listener listener) {
        return REGISTRY.register(request, storyKey, listener);
    }

    public static void unregister(Object request) {
        REGISTRY.unregister(request);
    }

    public static void enter(Object request) {
        REGISTRY.enter(request);
    }

    public static void exitActive() {
        REGISTRY.exitActive();
    }

    public static void complete(Object request) {
        REGISTRY.complete(request);
    }

    public static boolean isPending(StorySeenKey storyKey) {
        return REGISTRY.isPending(storyKey);
    }

    public static boolean isActive() {
        return REGISTRY.isActive();
    }

    public static boolean resolveSeenStatus(boolean seenStatus, boolean anonymousStories) {
        return anonymousStories && !isActive() ? true : seenStatus;
    }

    public interface Listener {
        void onComplete();

        void onFailure();
    }
}

final class RequestRegistry {
    private final LongSupplier clock;
    private final long permitTtlMillis;
    private final int maxPendingPermits;
    private final Consumer<StorySeenKey> completionConsumer;
    private final Map<Object, Permit> pendingPermits = new IdentityHashMap<>();
    private final Map<Object, Permit> activePermits = new IdentityHashMap<>();
    private final ThreadLocal<ScopeFrame> scope = new ThreadLocal<>();

    RequestRegistry(
            LongSupplier clock,
            long permitTtlMillis,
            int maxPendingPermits,
            Consumer<StorySeenKey> completionConsumer) {
        this.clock = clock;
        this.permitTtlMillis = permitTtlMillis;
        this.maxPendingPermits = maxPendingPermits;
        this.completionConsumer = completionConsumer;
    }

    synchronized boolean register(
            Object request,
            StorySeenKey storyKey,
            StorySeenRequestScope.Listener listener) {
        if (request == null) {
            return false;
        }

        long now = clock.getAsLong();
        removeExpired(now);
        if (activePermits.containsKey(request) || pendingPermits.containsKey(request)) {
            return false;
        }
        if (storyKey != null
                && (containsStory(pendingPermits, storyKey)
                || containsStory(activePermits, storyKey))) {
            return false;
        }
        if (pendingPermits.size() + activePermits.size() >= maxPendingPermits) {
            return false;
        }

        pendingPermits.put(request, new Permit(now + permitTtlMillis, storyKey, listener));
        return true;
    }

    void unregister(Object request) {
        Permit permit;
        synchronized (this) {
            permit = request == null ? null : pendingPermits.remove(request);
        }
        notifyFailure(permit);
    }

    void enter(Object request) {
        Permit permit = consumeAndActivate(request);
        boolean permitted = permit != null;
        ScopeFrame parent = scope.get();
        if (permitted || parent != null) {
            scope.set(new ScopeFrame(request, permitted, parent));
        }
    }

    void exitActive() {
        ScopeFrame current = scope.get();
        if (current != null) {
            finish(current.request, false);
        }
    }

    void complete(Object request) {
        finish(request, true);
    }

    private void finish(Object request, boolean completed) {
        ScopeFrame current = scope.get();
        if (current == null) {
            return;
        }
        if (current.request != request) {
            notifyFailure(discardActive(current.request));
            notifyFailure(discardActive(request));
            scope.remove();
            return;
        }
        Permit permit = current.permitted
                ? (completed ? activePermit(request) : discardActive(request))
                : null;
        if (current.parent == null) {
            scope.remove();
        } else {
            scope.set(current.parent);
        }
        if (permit == null) {
            return;
        }
        if (!completed) {
            notifyFailure(permit);
            return;
        }
        try {
            if (permit.storyKey != null) {
                completionConsumer.accept(permit.storyKey);
            }
            notifyComplete(permit);
        } catch (Throwable ignored) {
            notifyFailure(permit);
        } finally {
            discardActive(request);
        }
    }

    boolean isActive() {
        ScopeFrame current = scope.get();
        return current != null && current.permitted;
    }

    synchronized boolean isPending(StorySeenKey storyKey) {
        if (storyKey == null) {
            return false;
        }
        removeExpired(clock.getAsLong());
        return containsStory(pendingPermits, storyKey) || containsStory(activePermits, storyKey);
    }

    private synchronized Permit consumeAndActivate(Object request) {
        if (request == null) {
            return null;
        }

        long now = clock.getAsLong();
        removeExpired(now);
        Permit permit = pendingPermits.remove(request);
        if (permit == null || permit.deadline < now) {
            return null;
        }
        activePermits.put(request, permit);
        return permit;
    }

    private synchronized Permit discardActive(Object request) {
        return request == null ? null : activePermits.remove(request);
    }

    private synchronized Permit activePermit(Object request) {
        return request == null ? null : activePermits.get(request);
    }

    private static boolean containsStory(Map<Object, Permit> permits, StorySeenKey storyKey) {
        for (Permit permit : permits.values()) {
            if (storyKey.equals(permit.storyKey)) {
                return true;
            }
        }
        return false;
    }

    private void removeExpired(long now) {
        Iterator<Permit> permits = pendingPermits.values().iterator();
        while (permits.hasNext()) {
            Permit permit = permits.next();
            if (permit.deadline < now) {
                permits.remove();
                notifyFailure(permit);
            }
        }
    }

    private static void notifyComplete(Permit permit) {
        if (permit.listener == null) {
            return;
        }
        try {
            permit.listener.onComplete();
        } catch (Throwable ignored) {
            // A UI callback cannot retain a completed request scope.
        }
    }

    private static void notifyFailure(Permit permit) {
        if (permit == null || permit.listener == null) {
            return;
        }
        try {
            permit.listener.onFailure();
        } catch (Throwable ignored) {
            // A UI callback cannot retain a failed request scope.
        }
    }

    private static final class Permit {
        private final long deadline;
        private final StorySeenKey storyKey;
        private final StorySeenRequestScope.Listener listener;

        private Permit(
                long deadline,
                StorySeenKey storyKey,
                StorySeenRequestScope.Listener listener) {
            this.deadline = deadline;
            this.storyKey = storyKey;
            this.listener = listener;
        }
    }

    private static final class ScopeFrame {
        private final Object request;
        private final boolean permitted;
        private final ScopeFrame parent;

        private ScopeFrame(Object request, boolean permitted, ScopeFrame parent) {
            this.request = request;
            this.permitted = permitted;
            this.parent = parent;
        }
    }
}
