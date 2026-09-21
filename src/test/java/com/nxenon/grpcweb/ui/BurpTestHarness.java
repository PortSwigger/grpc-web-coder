package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;

import javax.swing.JPanel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stands in for a running Burp so the editor tabs can be exercised for real.
 *
 * <p>Montoya's static factories — {@code ByteArray.byteArray}, {@code HttpRequest.httpRequest} —
 * delegate to an object factory that Burp installs at startup, and are null-pointer exceptions
 * outside it. Installing a small factory here means the code under test can use those factories
 * exactly as it does in production, instead of being restructured to suit the tests.
 */
final class BurpTestHarness {

    private BurpTestHarness() {
    }

    /** Installs the object factory. Safe to call repeatedly. */
    static void installObjectFactory() {
        if (ObjectFactoryLocator.FACTORY != null) {
            return;
        }
        MontoyaObjectFactory factory = mock(MontoyaObjectFactory.class);
        when(factory.byteArray(any(byte[].class)))
                .thenAnswer(invocation -> new TestByteArray(invocation.getArgument(0)));
        when(factory.byteArray(anyString()))
                .thenAnswer(invocation -> new TestByteArray(
                        invocation.getArgument(0, String.class).getBytes(StandardCharsets.UTF_8)));
        when(factory.byteArrayOfLength(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> new TestByteArray(
                        new byte[invocation.getArgument(0, Integer.class)]));
        ObjectFactoryLocator.FACTORY = factory;
    }

    /** A Burp API whose raw editors are backed by real, inspectable text buffers. */
    static MontoyaApi api(Map<String, RecordingEditor> editorsByCreationOrder) {
        installObjectFactory();
        MontoyaApi api = mock(MontoyaApi.class, RETURNS_DEEP_STUBS);
        when(api.userInterface().createRawEditor(any(EditorOptions[].class)))
                .thenAnswer(invocation -> {
                    RecordingEditor editor = new RecordingEditor();
                    editorsByCreationOrder.put(
                            "editor" + editorsByCreationOrder.size(), editor);
                    return editor;
                });
        return api;
    }

    static EditorCreationContext creationContext(EditorMode mode) {
        EditorCreationContext context = mock(EditorCreationContext.class, RETURNS_DEEP_STUBS);
        when(context.editorMode()).thenReturn(mode);
        return context;
    }

    /** A request with the given Content-Type and body. */
    static HttpRequestResponse requestWith(String contentType, String body) {
        installObjectFactory();
        HttpRequest request = mock(HttpRequest.class);
        when(request.headerValue("Content-Type")).thenReturn(contentType);
        when(request.headerValue("X-Grpc-Content-Type")).thenReturn(null);
        when(request.body()).thenReturn(new TestByteArray(body.getBytes(StandardCharsets.UTF_8)));
        when(request.withBody(any(ByteArray.class))).thenAnswer(invocation -> {
            ByteArray newBody = invocation.getArgument(0);
            return requestWith(contentType,
                    new String(newBody.getBytes(), StandardCharsets.UTF_8)).request();
        });

        HttpRequestResponse requestResponse = mock(HttpRequestResponse.class);
        when(requestResponse.request()).thenReturn(request);
        when(requestResponse.hasResponse()).thenReturn(false);
        return requestResponse;
    }

    /** A response carrying a Content-Encoding, for the decompression path. */
    static HttpRequestResponse encodedResponseWith(
            String contentType, String contentEncoding, byte[] body) {
        installObjectFactory();
        HttpResponse response = mock(HttpResponse.class);
        when(response.headerValue("Content-Type")).thenReturn(contentType);
        when(response.headerValue("X-Grpc-Content-Type")).thenReturn(null);
        when(response.headerValue("Content-Encoding")).thenReturn(contentEncoding);
        when(response.body()).thenReturn(new TestByteArray(body));

        HttpRequest request = mock(HttpRequest.class);
        when(request.url()).thenReturn("https://example.test/app.js");

        HttpRequestResponse requestResponse = mock(HttpRequestResponse.class);
        when(requestResponse.request()).thenReturn(request);
        when(requestResponse.hasResponse()).thenReturn(true);
        when(requestResponse.response()).thenReturn(response);
        return requestResponse;
    }

    /** A response with the given Content-Type and body. */
    static HttpRequestResponse responseWith(String contentType, String body) {
        installObjectFactory();
        HttpResponse response = mock(HttpResponse.class);
        when(response.headerValue("Content-Type")).thenReturn(contentType);
        when(response.headerValue("X-Grpc-Content-Type")).thenReturn(null);
        when(response.body()).thenReturn(new TestByteArray(body.getBytes(StandardCharsets.UTF_8)));
        when(response.withBody(any(ByteArray.class))).thenAnswer(invocation -> {
            ByteArray newBody = invocation.getArgument(0);
            return responseWith(contentType,
                    new String(newBody.getBytes(), StandardCharsets.UTF_8)).response();
        });

        HttpRequestResponse requestResponse = mock(HttpRequestResponse.class);
        when(requestResponse.request()).thenReturn(mock(HttpRequest.class));
        when(requestResponse.hasResponse()).thenReturn(true);
        when(requestResponse.response()).thenReturn(response);
        return requestResponse;
    }

    /** A {@link RawEditor} that simply holds its text, so a test can read and rewrite it. */
    static final class RecordingEditor implements RawEditor {
        private ByteArray contents = new TestByteArray(new byte[0]);
        private boolean editable;
        private boolean modified;

        String text() {
            return new String(contents.getBytes(), StandardCharsets.UTF_8);
        }

        /** Simulates the user typing, which is what makes {@code isModified()} true. */
        void type(String value) {
            contents = new TestByteArray(value.getBytes(StandardCharsets.UTF_8));
            modified = true;
        }

        boolean isEditable() {
            return editable;
        }

        @Override
        public void setEditable(boolean editable) {
            this.editable = editable;
        }

        @Override
        public ByteArray getContents() {
            return contents;
        }

        @Override
        public void setContents(ByteArray contents) {
            this.contents = contents;
            this.modified = false;
        }

        @Override
        public void setSearchExpression(String expression) {
        }

        @Override
        public boolean isModified() {
            return modified;
        }

        @Override
        public int caretPosition() {
            return 0;
        }

        @Override
        public java.util.Optional<burp.api.montoya.ui.Selection> selection() {
            return java.util.Optional.empty();
        }

        @Override
        public java.awt.Component uiComponent() {
            return new JPanel();
        }
    }

    /** A plain in-memory {@link ByteArray}; only what the extension actually calls is implemented. */
    static final class TestByteArray implements ByteArray {
        private final byte[] bytes;

        TestByteArray(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public byte getByte(int index) {
            return bytes[index];
        }

        @Override
        public void setByte(int index, byte value) {
            bytes[index] = value;
        }

        @Override
        public void setByte(int index, int value) {
            bytes[index] = (byte) value;
        }

        @Override
        public void setBytes(int index, byte... values) {
            System.arraycopy(values, 0, bytes, index, values.length);
        }

        @Override
        public void setBytes(int index, int... values) {
            for (int i = 0; i < values.length; i++) {
                bytes[index + i] = (byte) values[i];
            }
        }

        @Override
        public void setBytes(int index, ByteArray value) {
            setBytes(index, value.getBytes());
        }

        @Override
        public int length() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public ByteArray subArray(int start, int end) {
            return new TestByteArray(java.util.Arrays.copyOfRange(bytes, start, end));
        }

        @Override
        public ByteArray subArray(burp.api.montoya.core.Range range) {
            return subArray(range.startIndexInclusive(), range.endIndexExclusive());
        }

        @Override
        public ByteArray copy() {
            return new TestByteArray(bytes);
        }

        @Override
        public ByteArray copyToTempFile() {
            return copy();
        }

        @Override
        public int indexOf(ByteArray value) {
            return indexOf(new String(value.getBytes(), StandardCharsets.UTF_8));
        }

        @Override
        public int indexOf(String value) {
            return new String(bytes, StandardCharsets.UTF_8).indexOf(value);
        }

        @Override
        public int indexOf(ByteArray value, boolean caseSensitive) {
            return indexOf(value);
        }

        @Override
        public int indexOf(String value, boolean caseSensitive) {
            return indexOf(value);
        }

        @Override
        public int indexOf(ByteArray value, boolean caseSensitive, int from, int to) {
            return indexOf(value);
        }

        @Override
        public int indexOf(String value, boolean caseSensitive, int from, int to) {
            return indexOf(value);
        }

        @Override
        public int indexOf(java.util.regex.Pattern pattern) {
            java.util.regex.Matcher matcher =
                    pattern.matcher(new String(bytes, StandardCharsets.UTF_8));
            return matcher.find() ? matcher.start() : -1;
        }

        @Override
        public int indexOf(java.util.regex.Pattern pattern, int from, int to) {
            return indexOf(pattern);
        }

        @Override
        public int countMatches(ByteArray value) {
            return 0;
        }

        @Override
        public int countMatches(String value) {
            return 0;
        }

        @Override
        public int countMatches(ByteArray value, boolean caseSensitive) {
            return 0;
        }

        @Override
        public int countMatches(String value, boolean caseSensitive) {
            return 0;
        }

        @Override
        public int countMatches(ByteArray value, boolean caseSensitive, int from, int to) {
            return 0;
        }

        @Override
        public int countMatches(String value, boolean caseSensitive, int from, int to) {
            return 0;
        }

        @Override
        public int countMatches(java.util.regex.Pattern pattern) {
            return 0;
        }

        @Override
        public int countMatches(java.util.regex.Pattern pattern, int from, int to) {
            return 0;
        }

        @Override
        public ByteArray withAppended(byte... data) {
            byte[] result = java.util.Arrays.copyOf(bytes, bytes.length + data.length);
            System.arraycopy(data, 0, result, bytes.length, data.length);
            return new TestByteArray(result);
        }

        @Override
        public ByteArray withAppended(int... data) {
            byte[] converted = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                converted[i] = (byte) data[i];
            }
            return withAppended(converted);
        }

        @Override
        public ByteArray withAppended(String data) {
            return withAppended(data.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public ByteArray withAppended(ByteArray data) {
            return withAppended(data.getBytes());
        }

        @Override
        public java.util.Iterator<Byte> iterator() {
            return new java.util.Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < bytes.length;
                }

                @Override
                public Byte next() {
                    return bytes[index++];
                }
            };
        }

        @Override
        public String toString() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Convenience for tests that only need a map to collect created editors. */
    static Map<String, RecordingEditor> editorMap() {
        return new HashMap<>();
    }
}
