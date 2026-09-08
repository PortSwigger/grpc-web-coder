package com.nxenon.grpcweb.ui;

import com.nxenon.grpcweb.codec.GrpcWebFormat;
import com.nxenon.grpcweb.ui.ExtensionSettings.DetectionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionSettingsTest {

    @Nested
    class Defaults {

        @Test
        @DisplayName("a fresh install detects automatically and decodes responses")
        void freshInstallDefaults() {
            ExtensionSettings settings = new ExtensionSettings(new FakePreferences());
            assertSame(DetectionMode.AUTOMATIC, settings.detectionMode());
            assertTrue(settings.decodeResponses());
            assertFalse(settings.showTabAlways());
        }
    }

    @Nested
    class AutomaticDetection {

        private final ExtensionSettings settings = new ExtensionSettings(new FakePreferences());

        @Test
        @DisplayName("the Content-Type header decides the format")
        void contentTypeDecides() {
            assertSame(GrpcWebFormat.TEXT,
                    settings.resolveFormat("application/grpc-web-text", null));
            assertSame(GrpcWebFormat.PROTO,
                    settings.resolveFormat("application/grpc-web+proto", null));
        }

        @Test
        @DisplayName("X-Grpc-Content-Type is used when Content-Type does not say")
        void grpcHeaderIsAFallback() {
            assertSame(GrpcWebFormat.TEXT,
                    settings.resolveFormat("application/octet-stream",
                            "application/grpc-web-text"));
            assertSame(GrpcWebFormat.PROTO,
                    settings.resolveFormat(null, "application/grpc-web+proto"));
        }

        @Test
        @DisplayName("Content-Type wins over X-Grpc-Content-Type when both are present")
        void contentTypeTakesPrecedence() {
            assertSame(GrpcWebFormat.PROTO,
                    settings.resolveFormat("application/grpc-web+proto",
                            "application/grpc-web-text"));
        }

        @Test
        @DisplayName("a message with neither header is not claimed")
        void unrecognisedMessageIsNotClaimed() {
            assertNull(settings.resolveFormat(null, null));
            assertNull(settings.resolveFormat("application/json", null));
            assertNull(settings.resolveFormat("text/html", "text/html"));
        }
    }

    @Nested
    class ForcedFormats {

        @Test
        @DisplayName("forcing text overrides whatever the headers say")
        void forceTextOverridesHeaders() {
            ExtensionSettings settings = new ExtensionSettings(new FakePreferences());
            settings.setDetectionMode(DetectionMode.FORCE_TEXT);

            assertSame(GrpcWebFormat.TEXT, settings.resolveFormat("application/json", null));
            assertSame(GrpcWebFormat.TEXT, settings.resolveFormat(null, null));
            assertSame(GrpcWebFormat.TEXT,
                    settings.resolveFormat("application/grpc-web+proto", null));
        }

        @Test
        @DisplayName("forcing proto overrides whatever the headers say")
        void forceProtoOverridesHeaders() {
            ExtensionSettings settings = new ExtensionSettings(new FakePreferences());
            settings.setDetectionMode(DetectionMode.FORCE_PROTO);

            assertSame(GrpcWebFormat.PROTO, settings.resolveFormat("application/json", null));
            assertSame(GrpcWebFormat.PROTO,
                    settings.resolveFormat("application/grpc-web-text", null));
        }
    }

    @Nested
    class ShowTabAlways {

        @Test
        @DisplayName("with the tab pinned on, an unrecognised message is treated as text")
        void unrecognisedMessageFallsBackToText() {
            ExtensionSettings settings = new ExtensionSettings(new FakePreferences());
            settings.setShowTabAlways(true);

            assertSame(GrpcWebFormat.TEXT, settings.resolveFormat("application/json", null));
            assertSame(GrpcWebFormat.TEXT, settings.resolveFormat(null, null));
        }

        @Test
        @DisplayName("pinning the tab on does not override a header that does identify the format")
        void realHeadersStillWin() {
            ExtensionSettings settings = new ExtensionSettings(new FakePreferences());
            settings.setShowTabAlways(true);

            assertSame(GrpcWebFormat.PROTO,
                    settings.resolveFormat("application/grpc-web+proto", null));
        }
    }

    @Nested
    class Persistence {

        @Test
        @DisplayName("settings are read back from preferences on the next load")
        void settingsSurviveAReload() {
            FakePreferences preferences = new FakePreferences();

            ExtensionSettings first = new ExtensionSettings(preferences);
            first.setDetectionMode(DetectionMode.FORCE_PROTO);
            first.setShowTabAlways(true);
            first.setDecodeResponses(false);

            ExtensionSettings second = new ExtensionSettings(preferences);
            assertSame(DetectionMode.FORCE_PROTO, second.detectionMode());
            assertTrue(second.showTabAlways());
            assertFalse(second.decodeResponses());
        }

        @Test
        @DisplayName("an unrecognised stored mode falls back to automatic instead of throwing")
        void unknownStoredModeFallsBack() {
            FakePreferences preferences = new FakePreferences();
            preferences.putRawString("grpcWebCoder.detectionMode", "SOMETHING_ELSE");

            ExtensionSettings settings = new ExtensionSettings(preferences);
            assertSame(DetectionMode.AUTOMATIC, settings.detectionMode());
        }

        @Test
        @DisplayName("each detection mode round-trips through preferences")
        void everyModeRoundTrips() {
            for (DetectionMode mode : DetectionMode.values()) {
                FakePreferences preferences = new FakePreferences();
                new ExtensionSettings(preferences).setDetectionMode(mode);
                assertEquals(mode, new ExtensionSettings(preferences).detectionMode());
            }
        }
    }
}
