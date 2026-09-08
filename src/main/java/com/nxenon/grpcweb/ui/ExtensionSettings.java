package com.nxenon.grpcweb.ui;

import burp.api.montoya.persistence.Preferences;
import com.nxenon.grpcweb.codec.GrpcWebFormat;

/**
 * User-facing settings, persisted in Burp's preferences so they survive a restart.
 *
 * <p>The Jython version exposed five checkboxes that enabled and disabled one another by hand, and
 * the combinations were easy to get into a contradictory state. The same capability is expressed
 * here as one detection mode plus two independent toggles, so mutually exclusive choices are
 * mutually exclusive by construction.
 */
public final class ExtensionSettings {

    /** How the body encoding of a message is decided. */
    public enum DetectionMode {
        /** Read it from the Content-Type or X-Grpc-Content-Type header. */
        AUTOMATIC,
        /** Assume every message is {@code application/grpc-web-text}. */
        FORCE_TEXT,
        /** Assume every message is {@code application/grpc-web+proto}. */
        FORCE_PROTO;

        /** The format this mode forces, or {@code null} when detection is automatic. */
        GrpcWebFormat forcedFormat() {
            return switch (this) {
                case FORCE_TEXT -> GrpcWebFormat.TEXT;
                case FORCE_PROTO -> GrpcWebFormat.PROTO;
                case AUTOMATIC -> null;
            };
        }
    }

    private static final String KEY_DETECTION_MODE = "grpcWebCoder.detectionMode";
    private static final String KEY_SHOW_TAB_ALWAYS = "grpcWebCoder.showTabAlways";
    private static final String KEY_DECODE_RESPONSES = "grpcWebCoder.decodeResponses";

    private final Preferences preferences;

    // Written from the settings tab on the event dispatch thread and read from isEnabledFor,
    // which Burp may call from another thread. volatile is all the safety these three need:
    // each is written independently and no invariant spans them.
    private volatile DetectionMode detectionMode = DetectionMode.AUTOMATIC;
    private volatile boolean showTabAlways;
    private volatile boolean decodeResponses = true;

    public ExtensionSettings(Preferences preferences) {
        this.preferences = preferences;
        load();
    }

    private void load() {
        String storedMode = preferences.getString(KEY_DETECTION_MODE);
        if (storedMode != null) {
            try {
                detectionMode = DetectionMode.valueOf(storedMode);
            } catch (IllegalArgumentException e) {
                // A preference written by a different version; fall back to the default.
                detectionMode = DetectionMode.AUTOMATIC;
            }
        }
        Boolean storedShowTab = preferences.getBoolean(KEY_SHOW_TAB_ALWAYS);
        if (storedShowTab != null) {
            showTabAlways = storedShowTab;
        }
        Boolean storedDecodeResponses = preferences.getBoolean(KEY_DECODE_RESPONSES);
        if (storedDecodeResponses != null) {
            decodeResponses = storedDecodeResponses;
        }
    }

    public DetectionMode detectionMode() {
        return detectionMode;
    }

    public void setDetectionMode(DetectionMode detectionMode) {
        this.detectionMode = detectionMode;
        preferences.setString(KEY_DETECTION_MODE, detectionMode.name());
    }

    /**
     * Whether the tab appears on messages with no recognised gRPC-Web content type, so a body can
     * still be decoded when a target uses an unusual header.
     */
    public boolean showTabAlways() {
        return showTabAlways;
    }

    public void setShowTabAlways(boolean showTabAlways) {
        this.showTabAlways = showTabAlways;
        preferences.setBoolean(KEY_SHOW_TAB_ALWAYS, showTabAlways);
    }

    public boolean decodeResponses() {
        return decodeResponses;
    }

    public void setDecodeResponses(boolean decodeResponses) {
        this.decodeResponses = decodeResponses;
        preferences.setBoolean(KEY_DECODE_RESPONSES, decodeResponses);
    }

    /**
     * Decides the body encoding for a message.
     *
     * @param contentTypeHeader      the {@code Content-Type} value, or {@code null}
     * @param grpcContentTypeHeader  the {@code X-Grpc-Content-Type} value, or {@code null}
     * @return the format to use, or {@code null} if this message is not gRPC-Web
     */
    public GrpcWebFormat resolveFormat(String contentTypeHeader, String grpcContentTypeHeader) {
        GrpcWebFormat forced = detectionMode.forcedFormat();
        if (forced != null) {
            return forced;
        }
        GrpcWebFormat fromContentType = GrpcWebFormat.fromContentType(contentTypeHeader);
        if (fromContentType != null) {
            return fromContentType;
        }
        // Some deployments put the real content type here and send a generic Content-Type,
        // so it is checked as a fallback rather than as a separate user-selected mode.
        GrpcWebFormat fromGrpcHeader = GrpcWebFormat.fromContentType(grpcContentTypeHeader);
        if (fromGrpcHeader != null) {
            return fromGrpcHeader;
        }
        // Nothing identified the message. With the tab pinned on, guess the text form, which is
        // the one whose framing is visible enough to tell at a glance whether the guess was right.
        return showTabAlways ? GrpcWebFormat.TEXT : null;
    }
}
