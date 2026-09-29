package com.am.server.system;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** {@link ResumableBackfill.Store} 的内存实现：sys_config 的替身，可编排写失败。 */
final class InMemoryBackfillStore implements ResumableBackfill.Store {

    final Set<String> markers = new HashSet<>();
    final Map<String, String> values = new HashMap<>();
    /** 为 true 时 write / writeMarker 抛 SQLException（模拟库抖动）。 */
    boolean failWrites;

    @Override
    public boolean markerExists(String key) {
        return markers.contains(key);
    }

    @Override
    public void writeMarker(String key, String description) throws SQLException {
        if (failWrites) {
            throw new SQLException("write failed");
        }
        markers.add(key);
    }

    @Override
    public String read(String key) {
        return values.get(key);
    }

    @Override
    public void write(String key, String value, String description) throws SQLException {
        if (failWrites) {
            throw new SQLException("write failed");
        }
        values.put(key, value);
    }

    @Override
    public void delete(String key) {
        values.remove(key);
    }
}
