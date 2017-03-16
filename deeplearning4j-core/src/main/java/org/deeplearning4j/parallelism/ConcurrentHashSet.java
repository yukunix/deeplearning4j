package org.deeplearning4j.parallelism;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is simplified ConcurrentHashSet implementation
 *
 * PLEASE NOTE: This class does NOT implement real equals & hashCode
 *
 * @author raver119@gmail.com
 */
// TODO: add equals/hashcode if needed
public class ConcurrentHashSet<E> implements Set<E>, Serializable {
    private static final long serialVersionUID = 123456789L;

    // we're using concurrenthashmap behind the scenes
    private ConcurrentHashMap<E, Boolean> backingMap;



    public ConcurrentHashSet() {
        backingMap = new ConcurrentHashMap<>();
    }

    public ConcurrentHashSet(@NonNull Collection<E> collection) {
        this();
        addAll(collection);
    }


    @Override
    public int size() {
        return backingMap.size();
    }

    @Override
    public boolean isEmpty() {
        return backingMap.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return backingMap.containsKey(o);
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private Iterator<Map.Entry<E, Boolean>> iterator = backingMap.entrySet().iterator();

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public E next() {
                return iterator.next().getKey();
            }

            @Override
            public void remove() {
                // do nothing
            }
        };
    }

    @Override
    public Object[] toArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(@NonNull E e) {
        Boolean ret = backingMap.putIfAbsent(e, Boolean.TRUE);

        return ret == null;
    }

    @Override
    public boolean remove(Object o) {
        return backingMap.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o))
                return false;
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        for (E e : c)
            add(e);

        return true;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        for (Object o : c)
            remove(o);

        return true;
    }

    @Override
    public void clear() {
        backingMap.clear();
    }
}
