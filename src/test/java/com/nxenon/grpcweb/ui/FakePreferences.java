package com.nxenon.grpcweb.ui;

import burp.api.montoya.persistence.Preferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link Preferences} for tests, so settings persistence can be exercised without a
 * running Burp. Only the string and boolean families are used by this extension; the rest are
 * implemented for completeness of the interface.
 */
final class FakePreferences implements Preferences {

    private final Map<String, String> strings = new HashMap<>();
    private final Map<String, Boolean> booleans = new HashMap<>();
    private final Map<String, Byte> bytes = new HashMap<>();
    private final Map<String, Short> shorts = new HashMap<>();
    private final Map<String, Integer> integers = new HashMap<>();
    private final Map<String, Long> longs = new HashMap<>();

    @Override
    public String getString(String key) {
        return strings.get(key);
    }

    @Override
    public void setString(String key, String value) {
        strings.put(key, value);
    }

    @Override
    public void deleteString(String key) {
        strings.remove(key);
    }

    @Override
    public Set<String> stringKeys() {
        return strings.keySet();
    }

    @Override
    public Boolean getBoolean(String key) {
        return booleans.get(key);
    }

    @Override
    public void setBoolean(String key, boolean value) {
        booleans.put(key, value);
    }

    @Override
    public void deleteBoolean(String key) {
        booleans.remove(key);
    }

    @Override
    public Set<String> booleanKeys() {
        return booleans.keySet();
    }

    @Override
    public Byte getByte(String key) {
        return bytes.get(key);
    }

    @Override
    public void setByte(String key, byte value) {
        bytes.put(key, value);
    }

    @Override
    public void deleteByte(String key) {
        bytes.remove(key);
    }

    @Override
    public Set<String> byteKeys() {
        return bytes.keySet();
    }

    @Override
    public Short getShort(String key) {
        return shorts.get(key);
    }

    @Override
    public void setShort(String key, short value) {
        shorts.put(key, value);
    }

    @Override
    public void deleteShort(String key) {
        shorts.remove(key);
    }

    @Override
    public Set<String> shortKeys() {
        return shorts.keySet();
    }

    @Override
    public Integer getInteger(String key) {
        return integers.get(key);
    }

    @Override
    public void setInteger(String key, int value) {
        integers.put(key, value);
    }

    @Override
    public void deleteInteger(String key) {
        integers.remove(key);
    }

    @Override
    public Set<String> integerKeys() {
        return integers.keySet();
    }

    @Override
    public Long getLong(String key) {
        return longs.get(key);
    }

    @Override
    public void setLong(String key, long value) {
        longs.put(key, value);
    }

    @Override
    public void deleteLong(String key) {
        longs.remove(key);
    }

    @Override
    public Set<String> longKeys() {
        return longs.keySet();
    }

    /** Simulates a preference written by a different version of the extension. */
    void putRawString(String key, String value) {
        strings.put(key, value);
    }
}
