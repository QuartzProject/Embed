package org.quartzlang.embed;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.quartzlang.jvm.HandleMapper;
import org.quartzlang.jvm.Type;

/** Java objects lent to one module as {@code @handle} values: the same object always gets the same id. */
public final class Handles {
    private final Map<Object, Long> ids = new IdentityHashMap<>();
    private final Map<Long, Object> objects = new HashMap<>();
    private long next = 1;

    private final HandleMapper mapper = new HandleMapper() {
        @Override
        public Object object(long id, Type type) {
            return get(id);
        }

        @Override
        public long id(Object object, Type type) {
            return idOf(object);
        }
    };

    /** This table as the bindings' {@link HandleMapper}, for {@code Values.to} and {@code Values.from}. */
    public HandleMapper mapper() {
        return mapper;
    }

    /** The id {@code object} travels as, registering it the first time. */
    public synchronized long idOf(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("a handle cannot be null");
        }
        Long known = ids.get(object);
        if (known != null) {
            return known;
        }
        long id = next++;
        ids.put(object, id);
        objects.put(id, object);
        return id;
    }

    /** The object behind {@code id}; throws when Quartz code passes an id this table never handed out. */
    public synchronized Object get(long id) {
        Object o = objects.get(id);
        if (o == null) {
            throw new IllegalArgumentException("no host object has handle " + id);
        }
        return o;
    }

    public <T> T get(long id, Class<T> type) {
        Object o = get(id);
        if (!type.isInstance(o)) {
            throw new IllegalArgumentException("handle " + id + " is a " + o.getClass().getName() + ", not a "
                    + type.getName());
        }
        return type.cast(o);
    }

    /** Forgets {@code object}, so it can be collected; Quartz code still holding its id gets an error on use. */
    public synchronized void release(Object object) {
        Long id = ids.remove(object);
        if (id != null) {
            objects.remove(id);
        }
    }

    public synchronized int size() {
        return objects.size();
    }
}
