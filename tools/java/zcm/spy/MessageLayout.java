package zcm.spy;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** An expanded row index. Uniform arrays use arithmetic, not one node per element. */
final class MessageLayout
{
    static final class NeedsBackground extends RuntimeException {}
    final Node root;
    final MessageValue.Path path;
    final long rows;
    private final long skip;

    MessageLayout(Object message, MessageValue.Path path, int first, int limit, int budget, BooleanSupplier cancelled) {
        this.path = path;
        Object value = path.read(message);
        Builder builder = new Builder(budget, cancelled);
        root = builder.build(value, path.declaredType, first, limit, new IdentityHashMap<Object, Boolean>(), path.recursive(message));
        skip = root.leaf ? 0 : 1; rows = root.rows - skip;
    }

    ObjectPanel.Row row(long index) { return index < 0 || index >= rows ? null : root.row(index + skip, path); }
    long indexOf(MessageValue.Path wanted) {
        if (wanted.steps.length < path.steps.length) return -1;
        for (int i = 0; i < path.steps.length; i++) if (!path.steps[i].equals(wanted.steps[i])) return -1;
        long index = root.indexOf(wanted.steps, path.steps.length);
        return index < skip ? -1 : index - skip;
    }

    private static final class Entry {
        final Field[] steps;
        final Class<?> type;
        Entry(Field[] steps, Class<?> type) { this.steps = steps; this.type = type; }
    }
    private static final class Schema {
        final ArrayList<Entry> entries = new ArrayList<Entry>();
        boolean add(Class<?> type, ArrayList<Field> steps, Set<Class<?>> ancestors) {
            if (entries.size() >= 4096 || type.isArray()) return false;
            entries.add(new Entry(steps.toArray(new Field[0]), type));
            if (MessageValue.scalar(type)) return true;
            if (type == Object.class || type.isInterface() || Modifier.isAbstract(type.getModifiers()) || !ancestors.add(type)) return false;
            for (Field field : MessageValue.fields(type)) {
                steps.add(field);
                if (!add(field.getType(), steps, ancestors)) return false;
                steps.remove(steps.size() - 1);
            }
            ancestors.remove(type); return true;
        }
    }
    private static final ClassValue<Schema> SCHEMAS = new ClassValue<Schema>() {
        protected Schema computeValue(Class<?> type) {
            Schema schema = new Schema();
            return schema.add(type, new ArrayList<Field>(), new HashSet<Class<?>>()) ? schema : null;
        }
    };

    static final class Node {
        final Object value;
        final Class<?> type;
        final boolean leaf, recursive;
        int start, end;
        Schema uniform;
        Field[] fields;
        Node[] children;
        long[] ends;
        long rows = 1;
        Node(Object value, Class<?> type, boolean recursive) {
            this.value = value; this.type = type; this.recursive = recursive;
            leaf = recursive || value == null || value == MessageValue.MISSING || MessageValue.scalar(value.getClass());
        }
        ObjectPanel.Row row(long offset, MessageValue.Path path) {
            if (offset == 0) return new ObjectPanel.Row(path, path.name, value, type, recursive);
            offset--;
            if (uniform != null) {
                int width = uniform.entries.size(), index = start + (int)(offset / width);
                Entry entry = uniform.entries.get((int)(offset % width));
                Object value = Array.get(this.value, index);
                MessageValue.Path item = path.append(index, this.value.getClass().getComponentType());
                for (Field step : entry.steps) { value = MessageValue.child(value, step); item = item.append(step, step.getType()); }
                return new ObjectPanel.Row(item, item.name, value, entry.type, false);
            }
            int child = childAt(offset);
            Object step = fields == null ? start + child : fields[child];
            return children[child].row(offset - (child == 0 ? 0 : ends[child - 1]), path.append(step, children[child].type));
        }
        private int childAt(long row) {
            int low = 0, high = ends.length;
            while (low < high) { int mid = (low + high) >>> 1; if (ends[mid] <= row) low = mid + 1; else high = mid; }
            return low;
        }
        long indexOf(Object[] steps, int offset) {
            if (offset == steps.length) return 0;
            if (leaf) return -1;
            if (uniform != null) {
                if (!(steps[offset] instanceof Integer)) return -1;
                int element = (Integer)steps[offset]; if (element < start || element >= end) return -1;
                for (int i = 0; i < uniform.entries.size(); i++) {
                    Field[] fields = uniform.entries.get(i).steps;
                    if (fields.length != steps.length - offset - 1) continue;
                    boolean match = true;
                    for (int j = 0; j < fields.length; j++) match &= fields[j].equals(steps[offset + 1 + j]);
                    if (match) return 1 + (element - start) * (long)uniform.entries.size() + i;
                }
                return -1;
            }
            int child = -1;
            if (fields == null && steps[offset] instanceof Integer) child = (Integer)steps[offset] - start;
            else if (fields != null) for (int i = 0; i < fields.length; i++) if (fields[i].equals(steps[offset])) { child = i; break; }
            if (child < 0 || child >= children.length) return -1;
            long nested = children[child].indexOf(steps, offset + 1);
            return nested < 0 ? -1 : 1 + (child == 0 ? 0 : ends[child - 1]) + nested;
        }
    }

    private static final class Builder {
        int budget;
        final BooleanSupplier cancelled;
        Builder(int budget, BooleanSupplier cancelled) { this.budget = budget; this.cancelled = cancelled; }
        Node build(Object value, Class<?> type, int first, int limit, IdentityHashMap<Object, Boolean> ancestors, boolean recursive) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (--budget < 0) throw new NeedsBackground();
            boolean composite = value != null && value != MessageValue.MISSING && !MessageValue.scalar(value.getClass());
            recursive |= composite && ancestors.containsKey(value);
            Node node = new Node(value, type, recursive);
            if (node.leaf) return node;
            ancestors.put(value, Boolean.TRUE);
            try {
                if (value.getClass().isArray()) {
                    node.start = Math.min(first, Array.getLength(value)); node.end = Math.min(limit, Array.getLength(value));
                    node.uniform = SCHEMAS.get(value.getClass().getComponentType());
                    if (node.uniform != null) { node.rows += Math.max(0, node.end - node.start) * (long)node.uniform.entries.size(); return node; }
                } else { node.fields = MessageValue.fields(value.getClass()); node.end = node.fields.length; }
                int count = Math.max(0, node.end - node.start);
                if (count > budget) throw new NeedsBackground();
                node.children = new Node[count]; node.ends = new long[count];
                for (int i = 0; i < count; i++) {
                    Object child; Class<?> childType;
                    if (node.fields == null) { child = Array.get(value, node.start + i); childType = value.getClass().getComponentType(); }
                    else { Field field = node.fields[i]; child = MessageValue.child(value, field); childType = field.getType(); }
                    node.children[i] = build(child, childType, 0, Integer.MAX_VALUE, ancestors, false);
                    node.rows += node.children[i].rows; node.ends[i] = node.rows - 1;
                }
                return node;
            } finally { ancestors.remove(value); }
        }
    }
}
