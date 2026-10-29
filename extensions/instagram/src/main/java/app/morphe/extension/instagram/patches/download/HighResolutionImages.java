/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import android.content.Context;

import com.instagram.common.session.UserSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.crimera.downloader.DownloadRequest;
import app.morphe.extension.crimera.downloader.MediaDownloader;
import app.morphe.extension.crimera.downloader.StorageUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.constants.PostType;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.MediaInterface;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.Utils;

/** Fetches a separate candidate list for a save; never updates Instagram's media objects. */
public final class HighResolutionImages {
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private HighResolutionImages() {}

    // Optional bridges: unsupported APKs keep ordinary downloads and omit this setting.
    public static boolean isAvailable() { return false; }
    private static String authorization(UserSession session) { return null; }
    private static String userAgent(Context context) { return null; }

    static void enqueue(MediaDownloader downloader, List<DownloadRequest> requests,
                        MediaData root, List<MediaData> children) {
        List<Target> targets = new ArrayList<>();
        UserSession session;
        String path;
        String parentId;
        String ownerId;
        try {
            if (!isAvailable() || !SharedPref.getBooleanPref(Settings.DOWNLOAD_HIGH_QUALITY_IMAGES)
                    || !StorageUtils.checkStoragePermissions() || requests.size() != children.size()) {
                downloader.enqueueBatch(requests);
                return;
            }
            session = root.getUserSession();
            if (session == null) {
                downloader.enqueueBatch(requests);
                return;
            }
            for (int i = 0; i < children.size(); i++) {
                MediaData child = children.get(i);
                // Video covers and explicitly chosen resolutions keep their existing paths.
                if (child.isVideo()) continue;
                MediaInterface image = child.getBestImage();
                DownloadRequest request = requests.get(i);
                if (image == null || !image.getUrl().equals(request.url)) continue;
                String id = child.getPostID();
                if (validId(id) && image.getWidth() > 0 && image.getHeight() > 0) {
                    targets.add(new Target(request, id, image.getWidth(), image.getHeight()));
                }
            }
            if (targets.isEmpty()) {
                downloader.enqueueBatch(requests);
                return;
            }
            parentId = root.getPostID();
            ownerId = root.getPostType() == PostType.STORY ? root.getUserData().getUserId() : null;
            if (!validId(parentId) || (ownerId != null && !validId(ownerId))) {
                downloader.enqueueBatch(requests);
                return;
            }
            path = ownerId == null ? "media/" + parentId + "/info/"
                    : "feed/reels_media/?reel_ids=" + ownerId;
        } catch (Exception | LinkageError ignored) {
            downloader.enqueueBatch(requests);
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                // One lookup per save/batch, including a single failure on rate limits or login expiry.
                JSONObject response = fetch(path, session);
                if (response != null) {
                    for (Target target : targets) {
                        Image image = find(response, parentId, ownerId, target.id, target.width, target.height);
                        if (image != null) promote(target.request, image);
                    }
                }
            } catch (Exception | LinkageError ignored) {
                // Do not log credentials or response bodies; the original sources remain usable.
            } finally {
                Utils.runOnMainThread(() -> downloader.enqueueBatch(requests));
            }
        });
    }

    private static JSONObject fetch(String path, UserSession session) throws Exception {
        String viewerId = session.getUserId();
        String auth = authorization(session);
        String agent = downloadUserAgent(userAgent(Utils.getContext()));
        if (!validId(viewerId) || auth == null || !auth.startsWith("Bearer ") || agent == null) return null;
        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://i.instagram.com/api/v1/" + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", auth);
            connection.setRequestProperty("User-Agent", agent);
            connection.setRequestProperty("IG-Intended-User-ID", viewerId);
            connection.setRequestProperty("X-IG-App-ID", "567067343352427");
            connection.setRequestProperty("X-IG-Capabilities", "3brTv10=");
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_RESPONSE_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String downloadUserAgent(String agent) {
        if (agent == null || !agent.matches(".*\\d+dpi; \\d+x\\d+.*")) return null;
        return agent.replaceFirst("(\\d+dpi; )\\d+x\\d+", "$14096x4096");
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[1-9][0-9]*");
    }

    private static boolean hasId(JSONObject media, String id) {
        String value = media.optString("pk", media.optString("id", ""));
        return id.equals(value.split("_", 2)[0]);
    }

    static Image find(JSONObject response, String parentId, String ownerId, String id, int width, int height) {
        if (!"ok".equals(response.optString("status")) || width <= 0 || height <= 0) return null;
        JSONArray items;
        if (ownerId != null) {
            JSONObject reels = response.optJSONObject("reels");
            JSONObject reel = reels == null ? null : reels.optJSONObject(ownerId);
            items = reel == null ? null : reel.optJSONArray("items");
        } else {
            items = response.optJSONArray("items");
        }
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject media = items.optJSONObject(i);
            if (media == null) continue;
            if (ownerId != null) {
                if (hasId(media, id)) return largerImage(media, width, height);
            } else if (hasId(media, parentId)) {
                if (hasId(media, id)) return largerImage(media, width, height);
                JSONArray children = media.optJSONArray("carousel_media");
                if (children == null) return null;
                for (int j = 0; j < children.length(); j++) {
                    JSONObject child = children.optJSONObject(j);
                    if (child != null && hasId(child, id)) return largerImage(child, width, height);
                }
            }
        }
        return null;
    }

    private static Image largerImage(JSONObject media, int width, int height) {
        if (media.optInt("media_type") != 1) return null;
        JSONObject versions = media.optJSONObject("image_versions2");
        JSONArray candidates = versions == null ? null : versions.optJSONArray("candidates");
        if (candidates == null) return null;
        Image best = null;
        long pixels = (long) width * height;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            int w = candidate.optInt("width"), h = candidate.optInt("height");
            String url = candidate.optString("url", "");
            long area = (long) w * h;
            if (w <= 0 || h <= 0 || area <= pixels || !isImageUrl(url)) continue;
            // Keep the saved composition when the server also offers cropped variants.
            if (Math.abs((double) w * height - (double) h * width) > (double) h * width * 0.02) continue;
            best = new Image(url, w, h);
            pixels = area;
        }
        return best;
    }

    private static boolean isImageUrl(String value) {
        try {
            URL url = new URL(value);
            return "https".equals(url.getProtocol()) && !url.getHost().isEmpty()
                    && url.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void promote(DownloadRequest request, Image image) {
        if (image.url.equals(request.url)) return;
        request.fallbackSources.add(0, new DownloadRequest.Source(request.url, null));
        request.url = image.url;
    }

    static final class Image {
        final String url;
        final int width, height;
        Image(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Target {
        final DownloadRequest request;
        final String id;
        final int width, height;
        Target(DownloadRequest request, String id, int width, int height) {
            this.request = request;
            this.id = id;
            this.width = width;
            this.height = height;
        }
    }
}
