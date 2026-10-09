package zcm.spy;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;

/** Reflection and formatting shared by the inspector and signal subscriptions. */
final class MessageValue
{
    static final Object MISSING = new Object();
    private static final ClassValue<Field[]> FIELDS = new ClassValue<Field[]>() {
        protected Field[] computeValue(Class<?> type) {
            ArrayList<Field> fields = new ArrayList<Field>();
            for (Field field : type.getFields()) {
                String name = field.getName();
                if (field.isSynthetic() || name.equals("ZCM_FINGERPRINT") ||
                    name.equals("ZCM_FINGERPRINT_BASE") || name.equals("IS_LITTLE_ENDIAN")) continue;
                fields.add(field);
            }
            return fields.toArray(new Field[0]);
        }
    };

    static Field[] fields(Class<?> type) { return FIELDS.get(type); }

    static boolean scalar(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || type == String.class ||
            type == Boolean.class || type == Character.class || Enum.class.isAssignableFrom(type);
    }

    static boolean plottable(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof Character;
    }

    static double number(Object value) {
        if (value instanceof Number) return ((Number)value).doubleValue();
        if (value instanceof Boolean) return (Boolean)value ? 1 : 0;
        if (value instanceof Character) return (Character)value;
        return Double.NaN;
    }

    static int count(Object value) {
        if (value == null || value == MISSING || scalar(value.getClass())) return 0;
        return value.getClass().isArray() ? Array.getLength(value) : fields(value.getClass()).length;
    }

    static Object child(Object parent, Object step) {
        if (parent == null || parent == MISSING) return MISSING;
        try {
            if (step instanceof Field) {
                Field field = (Field)step;
                if (!field.getDeclaringClass().isInstance(parent)) return MISSING;
                return field.get(parent);
            }
            int index = (Integer)step;
            if (!parent.getClass().isArray() || index < 0 || index >= Array.getLength(parent)) return MISSING;
            return Array.get(parent, index);
        } catch (IllegalAccessException | IllegalArgumentException error) { return MISSING; }
    }

    static String type(Class<?> type) {
        return type == null ? "—" : type.getTypeName();
    }

    static String format(Object value) {
        if (value == MISSING) return "(unavailable)";
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escaped((String)value, 512) + "\"";
        if (value instanceof Character) return "'" + escaped(value.toString(), 1) + "' (U+" +
            String.format(java.util.Locale.ROOT, "%04X", (int)(Character)value) + ")";
        if (value instanceof Byte) {
            int unsigned = ((Byte)value).intValue() & 255;
            return value + "  /  " + unsigned + " unsigned  /  0x" +
                "0123456789ABCDEF".charAt(unsigned >>> 4) + "0123456789ABCDEF".charAt(unsigned & 15);
        }
        if (value instanceof Enum<?>) return ((Enum<?>)value).name();
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        int count = count(value);
        return value.getClass().isArray() ? count + " elements" : count + " fields";
    }

    static String copyValue(Object value) {
        return value instanceof String || value instanceof Character ? value.toString() : format(value);
    }

    private static String escaped(String value, int limit) {
        StringBuilder text = new StringBuilder();
        int end = Math.min(value.length(), limit);
        // Keep supplementary Unicode characters intact at the preview boundary.
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        for (int i = 0; i < end; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n': text.append("\\n"); break;
                case '\r': text.append("\\r"); break;
                case '\t': text.append("\\t"); break;
                case '\\': text.append("\\\\"); break;
                case '"': text.append("\\\""); break;
                default:
                    if (Character.isISOControl(c)) text.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
                    else text.append(c);
            }
        }
        if (end < value.length()) text.append("… (" + value.length() + " characters; copy for full value)");
        return text.toString();
    }

    static final class Path {
        static final Path ROOT = new Path(new Object[0], Object.class);
        final Object[] steps;
        final Class<?> declaredType;
        final String name;
        final boolean constant;
        private final int hash;

        Path(Object[] steps, Class<?> declaredType) {
            this.steps = steps.clone(); this.declaredType = declaredType;
            StringBuilder name = new StringBuilder(); boolean constant = false;
            for (Object step : steps) {
                if (step instanceof Field) {
                    Field field = (Field)step;
                    if (name.length() > 0) name.append('.');
                    name.append(field.getName()); constant |= Modifier.isStatic(field.getModifiers());
                } else name.append('[').append(step).append(']');
            }
            this.name = name.toString(); this.constant = constant; hash = Arrays.hashCode(this.steps);
        }

        Path append(Object step, Class<?> type) {
            Object[] child = Arrays.copyOf(steps, steps.length + 1); child[steps.length] = step;
            return new Path(child, type);
        }

        Object read(Object root) {
            for (Object step : steps) root = child(root, step);
            return root;
        }

        boolean recursive(Object root) {
            IdentityHashMap<Object, Boolean> parents = new IdentityHashMap<Object, Boolean>();
            if (root != null) parents.put(root, Boolean.TRUE);
            for (Object step : steps) {
                root = child(root, step);
                if (root == null || root == MISSING || scalar(root.getClass())) return false;
                if (parents.put(root, Boolean.TRUE) != null) return true;
            }
            return false;
        }

        public boolean equals(Object other) { return other instanceof Path && Arrays.equals(steps, ((Path)other).steps); }
        public int hashCode() { return hash; }
    }
}
