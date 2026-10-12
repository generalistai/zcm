package zcm.spy;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

/** Searches message snapshots off the EDT; only chosen paths become subscriptions. */
final class SignalCatalog
{
    interface Source {
        List<ChannelData> channels();
        ObjectPanel inspector(ChannelData channel);
    }

    static final class Signal {
        final ChannelData channel;
        final String name, id;
        final Object[] path;
        final double value;
        Signal(ChannelData channel, String name, Object[] path, double value) {
            this.channel = channel; this.name = name; this.path = path; this.value = value;
            id = channel.name + "\t" + name;
        }
    }

    static final class Result {
        final List<Signal> signals = new ArrayList<Signal>();
        boolean limited;
    }

    static Result search(List<ChannelData> channels, String query, Set<String> favorites,
                         boolean onlyFavorites, BooleanSupplier cancelled)
    {
        Search search = new Search(query, favorites, onlyFavorites, cancelled);
        for (ChannelData channel : channels) {
            Object snapshot = channel.last;
            search.walk(channel, snapshot, "", new ArrayList<Object>(), new IdentityHashMap<Object, Boolean>());
            if (search.stopped()) break;
        }
        return search.result;
    }

    private static final class Search {
        final Result result = new Result();
        final Map<Class<?>, Field[]> fields = new HashMap<Class<?>, Field[]>();
        final String[] words;
        final Set<String> favorites;
        final boolean onlyFavorites;
        final BooleanSupplier cancelled;
        Search(String query, Set<String> favorites, boolean onlyFavorites, BooleanSupplier cancelled) {
            words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
            this.favorites = favorites; this.onlyFavorites = onlyFavorites; this.cancelled = cancelled;
        }
        boolean stopped() {
            if (cancelled.getAsBoolean()) return true;
            if (result.signals.size() >= 2000) {
                result.limited = true; return true;
            }
            return false;
        }
        void walk(ChannelData channel, Object value, String name, ArrayList<Object> path,
                  IdentityHashMap<Object, Boolean> ancestors) {
            if (stopped()) return;
            if (value == null) return;
            if (value instanceof Number) {
                String id = channel.name + "\t" + name;
                if (onlyFavorites && !favorites.contains(id)) return;
                String text = id.toLowerCase(Locale.ROOT);
                for (String word : words) if (!text.contains(word)) return;
                result.signals.add(new Signal(channel, name, path.toArray(), ((Number)value).doubleValue()));
                return;
            }
            if (path.size() >= 32) { result.limited = true; return; }
            Class<?> type = value.getClass();
            if (value instanceof String || value instanceof Boolean || value instanceof Character || type.isEnum()) return;
            if (ancestors.put(value, Boolean.TRUE) != null) return;
            try {
                if (type.isArray()) {
                    for (int i = 0, count = Array.getLength(value); i < count && !stopped(); i++) {
                        path.add(i);
                        walk(channel, Array.get(value, i), name + "[" + i + "]", path, ancestors);
                        path.remove(path.size() - 1);
                    }
                } else {
                    Field[] members = fields.get(type);
                    if (members == null) { members = type.getFields(); fields.put(type, members); }
                    for (Field field : members) {
                        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                        path.add(field);
                        try {
                            walk(channel, field.get(value), name.isEmpty() ? field.getName() : name + "." + field.getName(), path, ancestors);
                        } catch (IllegalAccessException ignored) {
                            // Only public, accessible numeric fields can be subscribed to.
                        } finally { path.remove(path.size() - 1); }
                        if (stopped()) break;
                    }
                }
            } finally { ancestors.remove(value); }
        }
    }

    /** Separate preference node: changes never replace other spy settings. */
    static final class Favorites {
        final Set<String> ids = new LinkedHashSet<String>();
        private final Preferences preferences;
        Favorites(Preferences preferences) {
            this.preferences = preferences;
            int count = Math.min(10000, preferences.getInt("count", 0));
            for (int i = 0; i < count; i++) {
                String id = preferences.get("signal." + i, "");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        void set(String id, boolean favorite) {
            if (favorite) ids.add(id); else ids.remove(id);
        }
        void save() throws java.util.prefs.BackingStoreException {
            int oldCount = preferences.getInt("count", 0), i = 0;
            for (String id : ids) preferences.put("signal." + i++, id);
            preferences.putInt("count", i);
            while (i < oldCount) preferences.remove("signal." + i++);
            preferences.flush();
        }
    }
}
