/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

public final class StorySeenButton {
    private static final String BUTTON_TAG = "piko_mark_story_seen_button";
    private static final String HEADER_MENU_ID_NAME = "header_menu_button";
    private static final int MAX_DEFERRED_BINDING_ATTEMPTS = 8;
    private static final long DEFERRED_BINDING_TIMEOUT_MILLIS = 250L;
    private static final AtomicLong CAPTURE_GENERATION = new AtomicLong();
    private static Object queuedItem;
    private static Object boundItem;
    private static WeakReference<View> queuedRoot = new WeakReference<>(null);
    private static WeakReference<View> boundAnchor = new WeakReference<>(null);
    private static WeakReference<View> transitionAnchor = new WeakReference<>(null);
    private static WeakReference<StorySeenBindingRetry> pendingBindingRetry =
            new WeakReference<>(null);
    private static long pendingBindingGeneration = -1L;
    private static final Map<ImageView, BindingState> BINDINGS = new WeakHashMap<>();
    private static final StorySeenBindingUpdates BINDING_UPDATES =
            new StorySeenBindingUpdates();

    static final class CapturedStory {
        final Object session;
        final Object item;
        final Object reel;
        final Object source;

        private CapturedStory(Object session, Object item, Object reel, Object source) {
            this.session = session;
            this.item = item;
            this.reel = reel;
            this.source = source;
        }
    }

    private static final class BindingState {
        private final CapturedStory story;
        private final WeakReference<ImageView> button;
        private final StorySeenKey storyKey;

        private BindingState(CapturedStory story, ImageView button, StorySeenKey storyKey) {
            this.story = story;
            this.button = new WeakReference<>(button);
            this.storyKey = storyKey;
        }
    }

    static final class ButtonPresentation {
        final boolean enabled;
        final float alpha;
        final String drawableName;
        final String colorAttributeName;
        final String contentDescriptionName;

        private ButtonPresentation(
                boolean enabled,
                float alpha,
                String drawableName,
                String colorAttributeName,
                String contentDescriptionName) {
            this.enabled = enabled;
            this.alpha = alpha;
            this.drawableName = drawableName;
            this.colorAttributeName = colorAttributeName;
            this.contentDescriptionName = contentDescriptionName;
        }
    }

    private StorySeenButton() {
    }

    public static void captureCurrentStoryFromProgress(
            Object session,
            Object item,
            Object reel,
            Object source,
            Object root) {
        captureCurrentStory(
                new CapturedStory(session, item, reel, source),
                root instanceof View ? (View) root : null,
                false
        );
    }

    public static void captureCurrentStoryFromHeader(
            Object session,
            Object item,
            Object reel,
            Object source,
            Object root) {
        captureCurrentStory(
                new CapturedStory(session, item, reel, source),
                root instanceof View ? (View) root : null,
                true
        );
    }

    public static void invalidateCurrentStory() {
        final long generation;
        final View anchor;
        synchronized (StorySeenButton.class) {
            generation = CAPTURE_GENERATION.incrementAndGet();
            queuedItem = null;
            queuedRoot = new WeakReference<>(null);
            View currentAnchor = boundAnchor.get();
            View retainedAnchor = transitionAnchor.get();
            anchor = currentAnchor != null ? currentAnchor : retainedAnchor;
            if (anchor != null) {
                transitionAnchor = new WeakReference<>(anchor);
            }
            boundItem = null;
            boundAnchor = new WeakReference<>(null);
        }
        Runnable invalidation = () -> {
            cancelPendingBindingThrough(generation);
            deactivateStaleAnchor(anchor);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidation.run();
        } else {
            Utils.runOnMainThread(invalidation);
        }
    }

    private static void captureCurrentStory(
            CapturedStory story,
            View root,
            boolean headerBinding) {
        final long generation;
        final View staleAnchor;
        synchronized (StorySeenButton.class) {
            View anchor = boundAnchor.get();
            View pendingRoot = queuedRoot.get();
            boolean boundStillUsable = anchor != null
                    && anchor.isAttachedToWindow()
                    && anchor.isShown();
            boolean differentPendingRoot = story.item == queuedItem
                    && root != null
                    && root != pendingRoot;
            if (!shouldScheduleCapture(
                    story.item,
                    queuedItem,
                    boundItem,
                    boundStillUsable,
                    headerBinding,
                    differentPendingRoot
            )) {
                return;
            }
            View retainedTransitionAnchor = transitionAnchor.get();
            staleAnchor = selectTransitionAnchor(
                    retainedTransitionAnchor,
                    staleBoundAnchorFor(story.item, headerBinding)
            );
            if (staleAnchor != null) {
                transitionAnchor = new WeakReference<>(staleAnchor);
            }
            queuedItem = story.item;
            queuedRoot = new WeakReference<>(root);
            generation = CAPTURE_GENERATION.incrementAndGet();
        }
        Runnable binding = () -> bindCapturedStory(
                generation,
                story,
                root,
                headerBinding,
                staleAnchor
        );
        if (root != null && Looper.myLooper() == Looper.getMainLooper()) {
            binding.run();
        } else {
            Utils.runOnMainThread(binding);
        }
    }

    private static void bindCapturedStory(
            long generation,
            CapturedStory story,
            View root,
            boolean headerBinding,
            View staleAnchor) {
        if (!isLatestCapture(generation, CAPTURE_GENERATION.get())) {
            return;
        }
        cancelPendingBindingThrough(generation);
        if (!isLatestCapture(generation, CAPTURE_GENERATION.get())) {
            return;
        }
        deactivateStaleAnchor(staleAnchor);

        View bound = null;
        boolean deferred = false;
        try {
            Activity activity = Utils.getActivity();
            int headerMenuId = ResourceUtils.getIdentifier(ResourceType.ID, HEADER_MENU_ID_NAME);
            View overflow = headerMenuId == 0
                    ? null
                    : root == null
                            ? activity == null ? null : activity.findViewById(headerMenuId)
                            : root.findViewById(headerMenuId);
            if (headerMenuId != 0 && root != null) {
                if (overflow == null) {
                    deferred = deferBinding(
                            generation,
                            story,
                            root,
                            headerBinding,
                            headerMenuId
                    );
                } else {
                    bound = bind(overflow, story, root, headerBinding);
                    if (bound == null) {
                        deferred = deferBinding(
                                generation,
                                story,
                                root,
                                headerBinding,
                                headerMenuId
                        );
                    }
                }
            } else {
                bound = bind(overflow, story, root, headerBinding);
            }
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to locate the story header menu", throwable);
        } finally {
            if (!deferred) {
                finishCapture(generation, story.item, bound);
            }
        }
    }

    private static boolean deferBinding(
            long generation,
            CapturedStory story,
            View root,
            boolean headerBinding,
            int headerMenuId) {
        StorySeenBindingRetry retry = new StorySeenBindingRetry(
                new ViewFrameHost(root),
                () -> isLatestCapture(generation, CAPTURE_GENERATION.get()),
                () -> attemptDeferredBinding(
                        generation,
                        story,
                        root,
                        headerBinding,
                        headerMenuId
                ),
                completed -> deferredBindingStopped(
                        generation,
                        story.item,
                        completed
                ),
                System::currentTimeMillis,
                MAX_DEFERRED_BINDING_ATTEMPTS,
                DEFERRED_BINDING_TIMEOUT_MILLIS
        );
        synchronized (StorySeenButton.class) {
            if (!isLatestCapture(generation, CAPTURE_GENERATION.get())
                    || queuedItem != story.item) {
                return false;
            }
            pendingBindingRetry = new WeakReference<>(retry);
            pendingBindingGeneration = generation;
        }
        boolean started = retry.start();
        if (!started) {
            clearPendingBinding(generation);
        }
        return started;
    }

    private static boolean attemptDeferredBinding(
            long generation,
            CapturedStory story,
            View root,
            boolean headerBinding,
            int headerMenuId) {
        View overflow = root.findViewById(headerMenuId);
        if (overflow == null) {
            return false;
        }
        View bound = bind(overflow, story, root, headerBinding);
        if (bound == null) {
            return false;
        }
        finishCapture(generation, story.item, bound);
        return true;
    }

    private static void deferredBindingStopped(
            long generation,
            Object item,
            boolean completed) {
        clearPendingBinding(generation);
        if (!completed) {
            finishCapture(generation, item, null);
        }
    }

    private static void cancelPendingBindingThrough(long generation) {
        final StorySeenBindingRetry retry;
        synchronized (StorySeenButton.class) {
            if (pendingBindingGeneration > generation) {
                return;
            }
            retry = pendingBindingRetry.get();
            pendingBindingRetry = new WeakReference<>(null);
            pendingBindingGeneration = -1L;
        }
        if (retry != null) {
            retry.cancel();
        }
    }

    private static synchronized void clearPendingBinding(long generation) {
        if (pendingBindingGeneration == generation) {
            pendingBindingRetry = new WeakReference<>(null);
            pendingBindingGeneration = -1L;
        }
    }

    static boolean isLatestCapture(long captureGeneration, long latestGeneration) {
        return captureGeneration == latestGeneration;
    }

    static boolean shouldScheduleCapture(
            Object item,
            Object queued,
            Object bound,
            boolean boundStillAttached,
            boolean headerBinding,
            boolean differentPendingRoot) {
        return item != null
                && (headerBinding || item != queued || differentPendingRoot)
                && (headerBinding || item != bound || !boundStillAttached);
    }

    private static synchronized void finishCapture(long generation, Object item, View anchor) {
        if (!isLatestCapture(generation, CAPTURE_GENERATION.get()) || queuedItem != item) {
            return;
        }
        queuedItem = null;
        queuedRoot = new WeakReference<>(null);
        transitionAnchor = new WeakReference<>(null);
        if (anchor != null) {
            boundItem = item;
            boundAnchor = new WeakReference<>(anchor);
        } else {
            boundItem = null;
            boundAnchor = new WeakReference<>(null);
        }
    }

    private static View bind(
            View overflow,
            CapturedStory story,
            View root,
            boolean headerBinding) {
        boolean anchorParent = overflow != null && overflow.getParent() instanceof ViewGroup;
        if (!anchorParent) {
            return null;
        }

        ViewGroup parent = (ViewGroup) overflow.getParent();
        ImageView button = findButton(parent);
        try {
            boolean anonymousStories = Pref.viewStoriesAnonymously();
            boolean overflowVisible = overflow.getVisibility() == View.VISIBLE;
            boolean available = StorySeenBridge.isAvailable(
                    story.session,
                    story.item,
                    story.reel,
                    story.source
            );
            boolean display = shouldDisplay(
                    anonymousStories,
                    overflowVisible,
                    available
            );
            if (!display) {
                boolean retryBinding = shouldRetryBinding(
                        anonymousStories,
                        overflowVisible,
                        available
                );
                if (button != null) {
                    button.setOnClickListener(null);
                    button.setEnabled(false);
                    if (shouldHideUnavailableButton(retryBinding)) {
                        button.setVisibility(View.GONE);
                    }
                }
                return retryBinding ? null : overflow;
            }

            if (button == null) {
                button = createButton(parent, overflow);
            } else {
                copyButtonGeometry(button, overflow);
            }
            keepAboveHeaderActions(button, parent);
            button.setVisibility(View.VISIBLE);
            StorySeenKey storyKey = StorySeenBridge.storyKey(story.session, story.item);
            BindingState bindingState = new BindingState(story, button, storyKey);
            synchronized (BINDINGS) {
                BINDINGS.put(button, bindingState);
            }
            BINDING_UPDATES.bind(
                    button,
                    storyKey,
                    () -> updateBinding(bindingState, StorySeenBridge.SeenState.MARKED),
                    () -> updateBinding(bindingState, StorySeenBridge.SeenState.UNMARKED)
            );
            StorySeenBridge.SeenState seenState = StorySeenBridge.state(
                    story.session,
                    story.item
            );
            ButtonPresentation presentation = applyPresentation(button, seenState);

            if (presentation.enabled) {
                button.setOnClickListener(
                        view -> markCurrentStory(bindingState));
            } else {
                button.setOnClickListener(null);
            }
            return bindingOutcome(overflow, true);
        } catch (Throwable throwable) {
            if (button != null) {
                button.setVisibility(View.GONE);
                button.setOnClickListener(null);
            }
            Logger.printException(() -> "Failed to bind mark story as seen button", throwable);
            return bindingOutcome(overflow, false);
        }
    }

    static <T> T selectTransitionAnchor(T retainedAnchor, T newCandidate) {
        return retainedAnchor != null ? retainedAnchor : newCandidate;
    }

    static <T> T bindingOutcome(T anchor, boolean successful) {
        return successful ? anchor : null;
    }

    static boolean shouldDisplay(boolean anonymousStories, boolean overflowVisible, boolean supportedStory) {
        return anonymousStories && overflowVisible && supportedStory;
    }

    static ButtonPresentation presentationFor(StorySeenBridge.SeenState seenState) {
        switch (seenState) {
            case MARKED:
                return new ButtonPresentation(
                        false,
                        0.50f,
                        UI.DRAWABLE_EYE_ICON,
                        "igds_color_primary_icon",
                        "piko_story_seen_sent"
                );
            case PENDING:
                return new ButtonPresentation(
                        false,
                        1.0f,
                        UI.DRAWABLE_EYE_ICON,
                        "igds_color_primary_icon",
                        "piko_story_seen_pending"
                );
            case UNMARKED:
            default:
                return new ButtonPresentation(
                        true,
                        1.0f,
                        UI.DRAWABLE_EYE_ICON,
                        "igds_color_primary_icon",
                        "piko_mark_story_seen"
                );
        }
    }

    static boolean shouldReplaceDrawable(boolean hasDrawable) {
        return !hasDrawable;
    }

    private static ButtonPresentation applyPresentation(
            ImageView button,
            StorySeenBridge.SeenState seenState) {
        ButtonPresentation presentation = presentationFor(seenState);
        button.setEnabled(presentation.enabled);
        button.setAlpha(presentation.alpha);
        button.setContentDescription(str(presentation.contentDescriptionName));
        if (shouldReplaceDrawable(button.getDrawable() != null)) {
            UI.setThemedIcon(button, presentation.drawableName);
        }
        button.setColorFilter(
                UI.getThemedColour(presentation.colorAttributeName),
                PorterDuff.Mode.SRC_ATOP
        );
        return presentation;
    }

    static boolean isCurrentBinding(Object expected, Object current) {
        return expected != null && expected == current;
    }

    static boolean shouldRetryBinding(
            boolean anonymousStories,
            boolean overflowVisible,
            boolean supportedStory) {
        return anonymousStories && !overflowVisible && supportedStory;
    }

    static boolean shouldHideUnavailableButton(boolean retryBinding) {
        return !retryBinding;
    }

    static boolean shouldInvalidateBoundStory(Object selectedItem, Object previousItem) {
        return previousItem != null && selectedItem != previousItem;
    }

    static boolean shouldDeferFirstDraw(
            boolean attached,
            int buttonWidth,
            int buttonHeight,
            int overflowWidth,
            int overflowHeight,
            boolean alreadyDeferred) {
        return attached
                && !alreadyDeferred
                && (buttonWidth == 0 || buttonHeight == 0)
                && overflowWidth > 0
                && overflowHeight > 0;
    }

    private static View staleBoundAnchorFor(Object selectedItem, boolean headerBinding) {
        if (!headerBinding && !shouldInvalidateBoundStory(selectedItem, boundItem)) {
            return null;
        }
        return boundAnchor.get();
    }

    private static void deactivateStaleAnchor(View anchor) {
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) anchor.getParent();
        ImageView button = findButton(parent);
        if (button != null) {
            button.setEnabled(false);
            button.setOnClickListener(null);
        }
    }

    private static void hideButton(ViewGroup parent) {
        ImageView button = findButton(parent);
        if (button != null) {
            button.setVisibility(View.GONE);
            button.setEnabled(false);
            button.setOnClickListener(null);
        }
    }

    private static final class ViewFrameHost implements
            StorySeenBindingRetry.FrameHost,
            ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final View root;
        private StorySeenBindingRetry.FrameCallback callback;
        private boolean observing;
        private boolean observingPreDraw;

        private ViewFrameHost(View root) {
            this.root = root;
        }

        @Override
        public boolean isAttached() {
            return root.isAttachedToWindow();
        }

        @Override
        public void addFrameCallback(StorySeenBindingRetry.FrameCallback value) {
            callback = value;
            observing = true;
            root.addOnAttachStateChangeListener(this);
            if (root.isAttachedToWindow()) {
                addPreDrawListener();
            }
        }

        @Override
        public void removeFrameCallback(StorySeenBindingRetry.FrameCallback value) {
            if (!observing || callback != value) {
                return;
            }
            observing = false;
            root.removeOnAttachStateChangeListener(this);
            removePreDrawListener();
            callback = null;
        }

        @Override
        public boolean onPreDraw() {
            StorySeenBindingRetry.FrameCallback current = callback;
            // Do not draw a native-only header after a deferred bind adds the Piko action.
            return current == null || !current.onFrame();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            StorySeenBindingRetry.FrameCallback current = callback;
            if (current == null) {
                return;
            }
            try {
                addPreDrawListener();
                current.onFrame();
            } catch (Throwable ignored) {
                current.onFrame();
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            StorySeenBindingRetry.FrameCallback current = callback;
            if (current != null) {
                current.onFrame();
            }
        }

        private void addPreDrawListener() {
            if (observingPreDraw) {
                return;
            }
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (!observer.isAlive()) {
                throw new IllegalStateException("Story root ViewTreeObserver is not alive");
            }
            observer.addOnPreDrawListener(this);
            observingPreDraw = true;
        }

        private void removePreDrawListener() {
            if (!observingPreDraw) {
                return;
            }
            observingPreDraw = false;
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
        }
    }

    private static ImageView createButton(ViewGroup parent, View overflow) {
        ImageView button = new ImageView(parent.getContext());
        button.setTag(BUTTON_TAG);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        copyButtonGeometry(button, overflow);

        int overflowIndex = parent.indexOfChild(overflow);
        int insertIndex = overflowIndex < 0 ? parent.getChildCount() : overflowIndex;
        ViewGroup.LayoutParams layoutParams = copyLayoutParams(overflow.getLayoutParams());
        parent.addView(button, insertIndex, layoutParams);
        new FirstLayoutDrawGate(button, parent, overflow).start();
        return button;
    }

    private static final class FirstLayoutDrawGate implements
            ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final ImageView button;
        private final ViewGroup parent;
        private final View overflow;
        private boolean observing;
        private boolean deferred;

        private FirstLayoutDrawGate(ImageView button, ViewGroup parent, View overflow) {
            this.button = button;
            this.parent = parent;
            this.overflow = overflow;
        }

        private void start() {
            ViewTreeObserver observer = parent.getViewTreeObserver();
            if (!observer.isAlive()) {
                return;
            }
            observing = true;
            parent.addOnAttachStateChangeListener(this);
            observer.addOnPreDrawListener(this);
        }

        @Override
        public boolean onPreDraw() {
            boolean defer = shouldDeferFirstDraw(
                    button.isAttachedToWindow(),
                    button.getWidth(),
                    button.getHeight(),
                    overflow.getWidth(),
                    overflow.getHeight(),
                    deferred
            );
            if (defer) {
                deferred = true;
                parent.requestLayout();
                return false;
            }
            stop();
            return true;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
        }

        private void stop() {
            if (!observing) {
                return;
            }
            observing = false;
            parent.removeOnAttachStateChangeListener(this);
            ViewTreeObserver observer = parent.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
        }
    }

    private static void keepAboveHeaderActions(ImageView button, ViewGroup parent) {
        float highestSiblingZ = 0.0f;
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child != button) {
                highestSiblingZ = Math.max(highestSiblingZ, child.getZ());
            }
        }

        // Instagram promotes header badges during its opening transition.
        button.setZ(drawZAbove(highestSiblingZ));
    }

    static float drawZAbove(float highestExistingZ) {
        return highestExistingZ + 1.0f;
    }

    private static void copyButtonGeometry(ImageView button, View anchor) {
        button.setPadding(
                anchor.getPaddingLeft(),
                anchor.getPaddingTop(),
                anchor.getPaddingRight(),
                anchor.getPaddingBottom()
        );
        button.setMinimumWidth(anchor.getMinimumWidth());
        button.setMinimumHeight(anchor.getMinimumHeight());
    }

    private static ImageView findButton(ViewGroup parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof ImageView && BUTTON_TAG.equals(child.getTag())) {
                return (ImageView) child;
            }
        }
        return null;
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source == null) {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        try {
            Constructor<?> constructor = source.getClass().getConstructor(ViewGroup.LayoutParams.class);
            return (ViewGroup.LayoutParams) constructor.newInstance(source);
        } catch (ReflectiveOperationException ignored) {
            if (source instanceof ViewGroup.MarginLayoutParams) {
                return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
            }
            return new ViewGroup.LayoutParams(source);
        }
    }

    private static void markCurrentStory(BindingState bindingState) {
        ImageView button = bindingState.button.get();
        if (button == null || !isCurrentBinding(bindingState, currentBinding(button))) {
            return;
        }
        CapturedStory story = bindingState.story;
        button.setEnabled(false);
        StorySeenRequestScope.Listener listener = resultListener(
                () -> Utils.runOnMainThread(
                        () -> Utils.showToastShort(str("piko_story_seen_sent"))),
                () -> BINDING_UPDATES.complete(bindingState.storyKey),
                () -> BINDING_UPDATES.fail(bindingState.storyKey)
        );
        if (StorySeenBridge.markSeen(
                story.session,
                story.item,
                story.reel,
                story.source,
                listener
        )) {
            StorySeenBridge.SeenState state = StorySeenBridge.state(story.session, story.item);
            applyPresentation(button, state);
            if (state == StorySeenBridge.SeenState.PENDING) {
                button.postDelayed(
                        () -> refreshExpiredPendingBinding(bindingState),
                        StorySeenRequestScope.PERMIT_TTL_MILLIS + 1L
                );
            }
            return;
        }

        applyPresentation(button, StorySeenBridge.SeenState.UNMARKED);
    }

    static StorySeenRequestScope.Listener resultListener(
            Runnable notifyMarked,
            Runnable updateMarkedBinding,
            Runnable updateFailedBinding) {
        AtomicBoolean resolved = new AtomicBoolean();
        return new StorySeenRequestScope.Listener() {
            @Override
            public void onComplete() {
                if (!resolved.compareAndSet(false, true)) {
                    return;
                }
                try {
                    notifyMarked.run();
                } finally {
                    updateMarkedBinding.run();
                }
            }

            @Override
            public void onFailure() {
                if (resolved.compareAndSet(false, true)) {
                    updateFailedBinding.run();
                }
            }
        };
    }

    private static BindingState currentBinding(ImageView button) {
        synchronized (BINDINGS) {
            return BINDINGS.get(button);
        }
    }

    private static void updateBinding(
            BindingState bindingState,
            StorySeenBridge.SeenState state) {
        ImageView button = bindingState.button.get();
        if (button == null) {
            return;
        }
        button.post(() -> {
            if (!isCurrentBinding(bindingState, currentBinding(button))) {
                return;
            }
            ButtonPresentation presentation = applyPresentation(button, state);
            if (presentation.enabled) {
                button.setOnClickListener(view -> markCurrentStory(bindingState));
            } else {
                button.setOnClickListener(null);
            }
        });
    }

    private static void refreshExpiredPendingBinding(BindingState bindingState) {
        ImageView button = bindingState.button.get();
        if (button == null || !isCurrentBinding(bindingState, currentBinding(button))) {
            return;
        }
        CapturedStory story = bindingState.story;
        updateBinding(bindingState, StorySeenBridge.state(story.session, story.item));
    }
}
