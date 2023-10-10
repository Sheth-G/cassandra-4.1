/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.service.accord;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import accord.utils.IntrusiveLinkedList;
import accord.utils.async.AsyncChains;
import org.apache.cassandra.cache.CacheSize;
import org.apache.cassandra.concurrent.ExecutorPlus;
import org.apache.cassandra.metrics.AccordStateCacheMetrics;
import org.apache.cassandra.metrics.CacheAccessMetrics;
import org.apache.cassandra.service.accord.AccordCachingState.Status;

import static accord.utils.Invariants.checkState;
import static java.lang.String.format;
import static org.apache.cassandra.service.accord.AccordCachingState.Status.EVICTED;
import static org.apache.cassandra.service.accord.AccordCachingState.Status.FAILED_TO_LOAD;
import static org.apache.cassandra.service.accord.AccordCachingState.Status.LOADED;
import static org.apache.cassandra.service.accord.AccordCachingState.Status.LOADING;
import static org.apache.cassandra.service.accord.AccordCachingState.Status.SAVING;

/**
 * Cache for AccordCommand and AccordCommandsForKey, available memory is shared between the two object types.
 * </p>
 * Supports dynamic object sizes. After each acquire/free cycle, the cacheable objects size is recomputed to
 * account for data added/removed during txn processing if it's modified flag is set
 */
public class AccordStateCache extends IntrusiveLinkedList<AccordCachingState<?,?>> implements CacheSize
{
    private static final Logger logger = LoggerFactory.getLogger(AccordStateCache.class);

    // Debug mode to verify that loading from journal + system tables results in
    // functionally identical (or superceding) command to the one we've just evicted.
    private static boolean VALIDATE_LOAD_ON_EVICT = false;

    @VisibleForTesting
    public static void validateLoadOnEvict(boolean value)
    {
        VALIDATE_LOAD_ON_EVICT = value;
    }

    private final Map<Object, AccordCachingState<?, ?>> cache = new HashMap<>();
    private final HashMap<Class<?>, Instance<?, ?, ?>> instances = new HashMap<>();

    private final ExecutorPlus loadExecutor, saveExecutor;

    private int unreferenced = 0;
    private long maxSizeInBytes;
    private long bytesCached = 0;

    @VisibleForTesting
    final AccordStateCacheMetrics metrics;

    public AccordStateCache(ExecutorPlus loadExecutor, ExecutorPlus saveExecutor, long maxSizeInBytes, AccordStateCacheMetrics metrics)
    {
        this.loadExecutor = loadExecutor;
        this.saveExecutor = saveExecutor;
        this.maxSizeInBytes = maxSizeInBytes;
        this.metrics = metrics;
    }

    @Override
    public void setCapacity(long sizeInBytes)
    {
        maxSizeInBytes = sizeInBytes;
        maybeEvictSomeNodes();
    }

    @Override
    public long capacity()
    {
        return maxSizeInBytes;
    }

    private void unlink(AccordCachingState<?, ?> node)
    {
        node.unlink();
        unreferenced--;
    }

    private void link(AccordCachingState<?, ?> node)
    {
        addLast(node);
        unreferenced++;
    }

    @SuppressWarnings("unchecked")
    private <K, V> void maybeUpdateSize(AccordCachingState<?, ?> node, ToLongFunction<?> estimator)
    {
        if (node.shouldUpdateSize())
        {
            long delta = ((AccordCachingState<K, V>) node).estimatedSizeOnHeapDelta((ToLongFunction<V>) estimator);
            bytesCached += delta;
            instanceForNode(node).bytesCached += delta;
        }
    }

    /*
     * Roughly respects LRU semantics when evicting. Might consider prioritising keeping MODIFIED nodes around
     * for longer to maximise the chances of hitting system tables fewer times (or not at all).
     */
    private void maybeEvictSomeNodes()
    {
        if (bytesCached <= maxSizeInBytes)
            return;

        Iterator<AccordCachingState<?, ?>> iter = this.iterator();
        while (iter.hasNext() && bytesCached > maxSizeInBytes)
        {
            AccordCachingState<?, ?> node = iter.next();
            checkState(node.references == 0);

            /*
             * TODO (expected, efficiency):
             *    can this be reworked so we're not skipping unevictable nodes everytime we try to evict?
             */
            Status status = node.status(); // status() call completes (if completeable)
            switch (status)
            {
                default: throw new IllegalStateException("Unhandled status " + status);
                case LOADED:
                    unlink(node);
                    evict(node);
                    break;
                case MODIFIED:
                    // schedule a save to disk, keep linked and in the cache map
                    Instance<?, ?, ?> instance = instanceForNode(node);
                    node.save(saveExecutor, instance.saveFunction);
                    maybeUpdateSize(node, instance.heapEstimator);
                    break;
                case SAVING:
                    // skip over until completes to LOADED or FAILED_TO_SAVE
                    break;
                case FAILED_TO_SAVE:
                    // TODO (consider): panic when a save fails
                    // permanently unlink, but keep in the map
                    unlink(node);
            }
        }
    }

    private boolean isInQueue(AccordCachingState<?, ?> node)
    {
        return node.isLinked();
    }

    private void evict(AccordCachingState<?, ?> node)
    {
        if (logger.isTraceEnabled())
            logger.trace("Evicting {} {} - {}", node.status(), node.key(), node.isLoaded() ? node.get() : null);

        checkState(!isInQueue(node));

        bytesCached -= node.lastQueriedEstimatedSizeOnHeap;
        Instance<?, ?, ?> instance = instanceForNode(node);
        instance.bytesCached -= node.lastQueriedEstimatedSizeOnHeap;

        if (node.status() == LOADED && VALIDATE_LOAD_ON_EVICT)
            instanceForNode(node).validateLoadEvicted(node);

        if (!node.hasListeners())
        {
            AccordCachingState<?, ?> self = cache.remove(node.key());
            if (self != null)
                instance.itemsCached--;
            checkState(self == node, "Leaked node detected; was attempting to remove %s but cache had %s", node, self);
        }
        else
        {
            node.markEvicted(); // keep the node in the cache to prevent transient listeners from being GCd
        }
    }

    private Instance<?, ?, ?> instanceForNode(AccordCachingState<?, ?> node)
    {
        return instances.get(node.key().getClass());
    }

    public <K, V, S extends AccordSafeState<K, V>> Instance<K, V, S> instance(
        Class<K> keyClass,
        Class<? extends K> realKeyClass,
        Function<AccordCachingState<K, V>, S> safeRefFactory,
        Function<K, V> loadFunction,
        BiFunction<V, V, Runnable> saveFunction,
        BiFunction<K, V, Boolean> validateFunction,
        ToLongFunction<V> heapEstimator)
    {
        Instance<K, V, S> instance =
            new Instance<>(keyClass, safeRefFactory, loadFunction, saveFunction, validateFunction, heapEstimator);

        if (instances.put(realKeyClass, instance) != null)
            throw new IllegalArgumentException(format("Cache instances for key type %s already exists", realKeyClass.getName()));

        return instance;
    }

    public class Instance<K, V, S extends AccordSafeState<K, V>> implements CacheSize
    {
        private final Class<K> keyClass;
        private final Function<AccordCachingState<K, V>, S> safeRefFactory;
        private Function<K, V> loadFunction;
        private BiFunction<V, V, Runnable> saveFunction;
        private final BiFunction<K, V, Boolean> validateFunction;
        private final ToLongFunction<V> heapEstimator;
        private long bytesCached;
        private int itemsCached;

        @VisibleForTesting
        final CacheAccessMetrics instanceMetrics;

        public Instance(
            Class<K> keyClass,
            Function<AccordCachingState<K, V>, S> safeRefFactory,
            Function<K, V> loadFunction,
            BiFunction<V, V, Runnable> saveFunction,
            BiFunction<K, V, Boolean> validateFunction,
            ToLongFunction<V> heapEstimator)
        {
            this.keyClass = keyClass;
            this.safeRefFactory = safeRefFactory;
            this.loadFunction = loadFunction;
            this.saveFunction = saveFunction;
            this.validateFunction = validateFunction;
            this.heapEstimator = heapEstimator;
            this.instanceMetrics = metrics.forInstance(keyClass);
        }

        public Stream<AccordCachingState<K, V>> stream()
        {
            return cache.entrySet().stream()
                        .filter(e -> keyClass.isAssignableFrom(e.getKey().getClass()))
                        .map(e -> (AccordCachingState<K, V>) e.getValue());
        }

        public S acquire(K key)
        {
            AccordCachingState<K, V> node = acquire(key, false);
            return safeRefFactory.apply(node);
        }

        public S acquireIfLoaded(K key)
        {
            AccordCachingState<K, V> node = acquire(key, true);
            if (node == null)
                return null;
            return safeRefFactory.apply(node);
        }

        private AccordCachingState<K, V> acquire(K key, boolean onlyIfLoaded)
        {
            incrementCacheQueries();
            @SuppressWarnings("unchecked")
            AccordCachingState<K, V> node = (AccordCachingState<K, V>) cache.get(key);
            return node == null
                 ? acquireAbsent(key, onlyIfLoaded)
                 : acquireExisting(node, onlyIfLoaded);
        }

        /*
         * Can only return a LOADING Node (or null)
         */
        private AccordCachingState<K, V> acquireAbsent(K key, boolean onlyIfLoaded)
        {
            incrementCacheMisses();
            if (onlyIfLoaded)
                return null;
            AccordCachingState<K, V> node = new AccordCachingState<>(key);
            node.load(loadExecutor, loadFunction);
            node.references++;

            if (cache.put(key, node) == null)
                itemsCached++;
            maybeUpdateSize(node, heapEstimator);
            metrics.objectSize.update(node.lastQueriedEstimatedSizeOnHeap);
            maybeEvictSomeNodes();
            return node;
        }

        /*
         * Can't return EVICTED or INITIALIZED
         */
        private AccordCachingState<K, V> acquireExisting(AccordCachingState<K, V> node, boolean onlyIfLoaded)
        {
            Status status = node.status(); // status() completes

            if (status.isLoaded())
                incrementCacheHits();
            else
                incrementCacheMisses();

            if (onlyIfLoaded && !status.isLoaded())
                return null;

            if (node.references == 0)
            {
                if (status == FAILED_TO_LOAD || status == EVICTED)
                    node.reset().load(loadExecutor, loadFunction);

                if (isInQueue(node))
                    unlink(node);
            }
            node.references++;

            return node;
        }

        public void release(S safeRef)
        {
            K key = safeRef.global().key();
            logger.trace("Releasing resources for {}: {}", key, safeRef);

            @SuppressWarnings("unchecked")
            AccordCachingState<K, V> node = (AccordCachingState<K, V>) cache.get(key);

            checkState(node != null, "node is null for %s", key);
            checkState(node.references > 0, "references (%d) are zero for %s (%s)", node.references, key, node);
            checkState(safeRef.global() == node);
            checkState(!isInQueue(node));

            if (safeRef.hasUpdate())
                node.set(safeRef.current());

            maybeUpdateSize(node, heapEstimator);

            if (--node.references == 0)
            {
                Status status = node.status(); // status() completes
                switch (status)
                {
                    default: throw new IllegalStateException("Unhandled status " + status);
                    case LOADING:
                    case FAILED_TO_LOAD:
                        logger.trace("Evicting {} with status {}", key, status);
                        evict(node);
                        break;
                    case LOADED:
                    case MODIFIED:
                    case SAVING:
                        logger.trace("Moving {} with status {} to eviction queue", key, status);
                        link(node);
                        break;
                    case FAILED_TO_SAVE:
                        break; // can never evict, so no point in adding to eviction queue either
                }
            }

            // TODO (performance, expected): triggering on every release is potentially heavy
            maybeEvictSomeNodes();
        }

        void validateLoadEvicted(AccordCachingState<?, ?> node)
        {
            @SuppressWarnings("unchecked")
            AccordCachingState<K, V> state = (AccordCachingState<K, V>) node;
            K key = state.key();
            V evicted = state.get();
            if (!validateFunction.apply(key, evicted))
                throw new IllegalStateException("Reloaded value for key " + key + " is not equal to or fuller than evicted value " + evicted);
        }

        @VisibleForTesting
        public AccordCachingState<K, V> getUnsafe(K key)
        {
            //noinspection unchecked
            return (AccordCachingState<K, V>) cache.get(key);
        }

        @VisibleForTesting
        public boolean isReferenced(K key)
        {
            //noinspection unchecked
            AccordCachingState<K, V> node = (AccordCachingState<K, V>) cache.get(key);
            return node != null && node.references > 0;
        }

        @VisibleForTesting
        public boolean isLoaded(K key)
        {
            //noinspection unchecked
            AccordCachingState<K, V> node = (AccordCachingState<K, V>) cache.get(key);
            return node != null && node.isLoaded();
        }

        @VisibleForTesting
        public boolean hasLoadResult(K key)
        {
            AccordCachingState<?, ?> node = cache.get(key);
            return node != null && node.status() == LOADING;
        }

        @VisibleForTesting
        public boolean hasSaveResult(K key)
        {
            AccordCachingState<?, ?> node = cache.get(key);
            return node != null && node.status() == SAVING;
        }

        @VisibleForTesting
        public void complete(K key)
        {
            AccordCachingState<?, ?> node = cache.get(key);
            if (node != null)
                node.complete();
        }

        private void incrementCacheQueries()
        {
            instanceMetrics.requests.mark();
            metrics.requests.mark();
        }

        private void incrementCacheHits()
        {
            instanceMetrics.hits.mark();
            metrics.hits.mark();
        }

        private void incrementCacheMisses()
        {
            instanceMetrics.misses.mark();
            metrics.misses.mark();
        }

        @VisibleForTesting
        public void unsafeSetLoadFunction(Function<K, V> loadFunction)
        {
            this.loadFunction = loadFunction;
        }

        @VisibleForTesting
        public void unsafeSetSaveFunction(BiFunction<V, V, Runnable> saveFunction)
        {
            this.saveFunction = saveFunction;
        }

        @Override
        public long capacity()
        {
            return AccordStateCache.this.capacity();
        }

        @Override
        public void setCapacity(long capacity)
        {
            throw new UnsupportedOperationException("Capacity is shared between all instances. Please set the capacity on the global cache");
        }

        @Override
        public int size()
        {
            return itemsCached;
        }

        @Override
        public long weightedSize()
        {
            return bytesCached;
        }
    }

    @VisibleForTesting
    void unsafeClear()
    {
        cache.clear();
        bytesCached = 0;
        metrics.reset();;
        instances.values().forEach(i -> {
            i.itemsCached = 0;
            i.bytesCached = 0;
            i.instanceMetrics.reset();
        });
        //noinspection StatementWithEmptyBody
        while (null != poll());
    }

    @VisibleForTesting
    AccordCachingState<?, ?> head()
    {
        Iterator<AccordCachingState<?, ?>> iter = iterator();
        return iter.hasNext() ? iter.next() : null;
    }

    @VisibleForTesting
    AccordCachingState<?, ?> tail()
    {
        AccordCachingState<?,?> last = null;
        Iterator<AccordCachingState<?, ?>> iter = iterator();
        while (iter.hasNext())
            last = iter.next();
        return last;
    }

    @VisibleForTesting
    public void awaitSaveResults()
    {
        for (AccordCachingState<?, ?> node : this)
            if (node.status() == SAVING)
                AsyncChains.awaitUninterruptibly(node.saving());
    }

    @VisibleForTesting
    int numReferencedEntries()
    {
        return cache.size() - unreferenced;
    }

    @VisibleForTesting
    int numUnreferencedEntries()
    {
        return unreferenced;
    }

    @Override
    public int size()
    {
        return cache.size();
    }

    @Override
    public long weightedSize()
    {
        return bytesCached;
    }

    @VisibleForTesting
    boolean keyIsReferenced(Object key)
    {
        AccordCachingState<?, ?> node = cache.get(key);
        return node != null && node.references > 0;
    }

    @VisibleForTesting
    boolean keyIsCached(Object key)
    {
        AccordCachingState<?, ?> node = cache.get(key);
        return node != null && node.status() != EVICTED;
    }

    @VisibleForTesting
    int references(Object key)
    {
        AccordCachingState<?, ?> node = cache.get(key);
        return node != null ? node.references : 0;
    }
}
