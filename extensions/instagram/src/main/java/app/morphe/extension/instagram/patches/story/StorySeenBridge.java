/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import app.morphe.extension.shared.Logger;
import com.instagram.common.session.UserSession;

public final class StorySeenBridge {
    private static final Operations NATIVE_OPERATIONS = new Operations() {
        @Override
        public Object buildRequest(Object session, Object item, Object reel) {
            return buildRequestNative(session, item, reel, null, null);
        }

        @Override
        public void markLocal(Object session, Object item, Object reel) {
            markLocalNative(session, item, reel, null, null);
        }

        @Override
        public void scheduleRequest(Object session, Object request) {
            scheduleRequestNative(session, request);
        }
    };
    private static final RequestPermits REQUEST_PERMITS = new RequestPermits() {
        @Override
        public boolean register(
                Object request,
                StorySeenKey storyKey,
                StorySeenRequestScope.Listener listener) {
            return StorySeenRequestScope.register(request, storyKey, listener);
        }

        @Override
        public void unregister(Object request) {
            StorySeenRequestScope.unregister(request);
        }
    };

    private StorySeenBridge() {
    }

    public static void captureFromPromptProgress(Object controller) {
        captureFromPromptProgressNative(controller, null, null, null, null);
    }

    public static void captureFromStandardProgress(Object controller) {
        captureFromStandardProgressNative(controller, null, null, null, null);
    }

    public static void captureFromCompactProgress(Object controller) {
        captureFromCompactProgressNative(controller, null, null, null, null);
    }

    public static void captureFromHeaderBind(
            Object session,
            Object item,
            Object reel,
            Object holder) {
        captureFromHeaderBindNative(session, item, reel, holder, null, null);
    }

    public static boolean isAvailable(Object session, Object item, Object reel, Object source) {
        try {
            return storyKey(session, item) != null && isAvailableForDisplay(
                    source,
                    isSelfNative(session, reel),
                    isSupportedNative(item, reel, null)
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAvailableForDisplay(Object source, boolean self, boolean supportedStory) {
        return isSupportedSource(source) && !self && supportedStory;
    }

    public static SeenState state(Object session, Object item) {
        try {
            StorySeenKey storyKey = storyKey(session, item);
            if (storyKey == null) {
                return SeenState.UNMARKED;
            }
            if (StorySeenState.contains(storyKey)) {
                return SeenState.MARKED;
            }
            return StorySeenRequestScope.isPending(storyKey)
                    ? SeenState.PENDING
                    : SeenState.UNMARKED;
        } catch (Throwable ignored) {
            return SeenState.UNMARKED;
        }
    }

    public static synchronized boolean markSeen(
            Object session,
            Object item,
            Object reel,
            Object source,
            StorySeenRequestScope.Listener listener) {
        try {
            StorySeenKey storyKey = storyKey(session, item);
            if (storyKey == null) {
                return false;
            }
            if (StorySeenState.contains(storyKey) || StorySeenRequestScope.isPending(storyKey)) {
                return true;
            }
            if (isSelfNative(session, reel) || !isSupportedNative(item, reel, null)) {
                return false;
            }
            return sendSupportedSource(
                    source,
                    session,
                    item,
                    reel,
                    storyKey,
                    NATIVE_OPERATIONS,
                    REQUEST_PERMITS,
                    listener
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasStableStoryId(String storyId) {
        return storyId != null && !storyId.isEmpty();
    }

    static StorySeenKey storyKey(Object session, String storyId) {
        if (!(session instanceof UserSession) || !hasStableStoryId(storyId)) {
            return null;
        }
        return StorySeenKey.from(((UserSession) session).getUserId(), storyId);
    }

    static StorySeenKey storyKey(Object session, Object item) {
        try {
            return storyKey(session, storyIdNative(item));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isSupportedSource(Object source) {
        if (!(source instanceof Enum<?>)) {
            return false;
        }

        String name = ((Enum<?>) source).name();
        return "USER_REEL".equals(name) || "PRIVATE_STORY_REEL".equals(name);
    }

    static boolean sendSupportedSource(
            Object source,
            Object session,
            Object item,
            Object reel,
            StorySeenKey storyKey,
            Operations operations,
            RequestPermits permits,
            StorySeenRequestScope.Listener listener) {
        if (!isSupportedSource(source)) {
            return false;
        }
        return send(session, item, reel, storyKey, operations, permits, listener);
    }

    static boolean send(
            Object session,
            Object item,
            Object reel,
            StorySeenKey storyKey,
            Operations operations,
            RequestPermits permits,
            StorySeenRequestScope.Listener listener) {
        final Object request;
        try {
            request = operations.buildRequest(session, item, reel);
        } catch (Throwable ignored) {
            return false;
        }
        if (request == null || !permits.register(request, storyKey, listener)) {
            return false;
        }

        try {
            operations.scheduleRequest(session, request);
        } catch (Throwable ignored) {
            permits.unregister(request);
            return false;
        }

        try {
            operations.markLocal(session, item, reel);
        } catch (Throwable throwable) {
            logLocalUpdateFailure(throwable);
            // The accepted server request is authoritative; keep its pending permit.
        }
        return true;
    }

    private static void logLocalUpdateFailure(Throwable throwable) {
        try {
            Logger.printException(() -> "Failed to update the selected story locally", throwable);
        } catch (Throwable ignored) {
            // Diagnostics cannot cancel a server request that was already accepted.
        }
    }

    public static boolean isSelfNative(Object session, Object reel) {
        return true;
    }

    public static void captureFromPromptProgressNative(
            Object controller,
            Object session,
            Object item,
            Object reel,
            Object source) {
    }

    public static void captureFromStandardProgressNative(
            Object controller,
            Object session,
            Object item,
            Object reel,
            Object source) {
    }

    public static void captureFromCompactProgressNative(
            Object controller,
            Object session,
            Object item,
            Object reel,
            Object source) {
    }

    public static void captureFromHeaderBindNative(
            Object session,
            Object item,
            Object reel,
            Object holder,
            Object root,
            Object source) {
    }

    public static boolean isSupportedNative(Object item, Object reel, Object scratch) {
        return false;
    }

    public static String storyIdNative(Object item) {
        return null;
    }

    public static Object buildRequestNative(
            Object session,
            Object item,
            Object reel,
            Object scratch,
            Object secondScratch) {
        return null;
    }

    public static void markLocalNative(
            Object session,
            Object item,
            Object reel,
            Object scratch,
            Object secondScratch) {
    }

    public static void scheduleRequestNative(Object session, Object request) {
        throw new IllegalStateException("Story seen bridge was not patched");
    }

    interface Operations {
        Object buildRequest(Object session, Object item, Object reel);

        void markLocal(Object session, Object item, Object reel);

        void scheduleRequest(Object session, Object request);
    }

    interface RequestPermits {
        boolean register(
                Object request,
                StorySeenKey storyKey,
                StorySeenRequestScope.Listener listener);

        void unregister(Object request);
    }

    public enum SeenState {
        UNMARKED,
        PENDING,
        MARKED
    }
}
