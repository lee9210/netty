/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * todo 以后理解
 *
 * 基于当前线程堆栈的轻量级对象池。
 * 用于线程对象的缓存，避免对象重复创建以及对象的回收利用
 *
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    /**
     * 表示一个不需要回收的包装对象
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {// 用于maxCapacityPerThread == 0时,关闭对象回收功能.
            // NOOP
        }
    };
    /**
     * 线程安全的自增计数器,用来做唯一标记的.
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * static变量, 生成并获取一个唯一id, 标记当前的线程.
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    /**
     * 每个线程的Stack最多缓存多少个对象
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    /**
     * 初始化容量
     */
    private static final int INITIAL_CAPACITY;
    /**
     * 最大可共享的容量
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    /**
     * WeakOrderQueue最大数量
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    /**
     * WeakOrderQueue中的数组DefaultHandle<?>[] elements容量
     */
    private static final int LINK_CAPACITY;
    /**
     * 掩码
     */
    private static final int RATIO;

    static {
        // 这里做到了各种参数都是可配置的, 可以根据实际的压测情况, 调节对象池的参数
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        //根据ratio获取一个掩码,默认为8,那么ratioMask二进制就是 "111"
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        // 通过修改maxCapacityPerThread=0可以关闭回收功能, 默认值是32768
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        // 获取当前线程对应的Stack
        Stack<T> stack = threadLocal.get();
        // 从对象池获取对象
        DefaultHandle<T> handle = stack.pop();
        // 没有对象,则调用子类的newObject方法创建新的对象
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> {
        void recycle(T object);
    }

    // DefaultHandle就是就是Stack的包装对象,持有stack的引用,可以回收自己到stack中;
    static final class DefaultHandle<T> implements Handle<T> {
        /**
         * 标记最新一次回收的线程id
         */
        private int lastRecycledId;
        /**
         * 也是一个标记,是用来回收前的校验的.
         */
        private int recycleId;

        /**
         * 标记是否已经被回收
         */
        boolean hasBeenRecycled;

        /**
         * 持有stack的引用
         */
        private Stack<?> stack;
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            //可以回收自己到stack中
            stack.push(this);
        }
    }

    /**
     * 这也是一个线程本地变量,每个线程都有自己的Map<Stack<?>, WeakOrderQueue>
     * 根据Stack可以获取到对应的WeakOrderQueue. 需要注意的是这边两个对象都有弱引用,WeakReference!
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            // 使用WeakHashMap,保证对key也就是Stack是弱引用; 一旦Stack没有强引用了, 会被回收的,WeakHashMap不会无限占用内存;
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {
        /**
         * 用于标记空的WeakOrderQueue,在达到WeakOrderQueue数量上限时放入一个这个,表示结束了.
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        /**
         * Link对象本身会作为读索引.
         * 这里为什么要继承一个AtomicInteger呢,因为这样Link就是一个线程安全的容器,保证了多线程安全和可见性.
         */
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            /**
             * 维护一个数组, 容量默认为16.
             */
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            /**
             * 读索引
             */
            private int readIndex;
            /**
             * 下一个索引. WeakOrderQueue有多个时, 之间遍历靠next指向下一个WeakOrderQueue.
             */
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        static final class Head {
            /**
             * 允许的最大共享容量
             */
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.

            /**
             * WeakOrderQueue对象GC前调用这个方法
             * @throws Throwable
             */
            @Override
            protected void finalize() throws Throwable {
                try {
                    //回收对象
                    super.finalize();
                } finally {
                    // 需要这个方法是因为这个被用在WeakHashMap中,会随时GC它
                    Link head = link;
                    link = null;
                    // 遍历WeakHashMap中所有的Link,直到link = null这样里面的Link对象没有引用了,都会被回收.
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }

            /**
             * availableSharedCapacity加上space,就是恢复前面减去的space大小
             * @param space
             */
            void reclaimSpace(int space) {
                assert space >= 0;
                // availableSharedCapacity和上面的方法会存在并发,所以采用原子类型.
                availableSharedCapacity.addAndGet(space);
            }

            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            /**
             * 容量不够就返回false; 够的话就减去space大小.
             *
             * @param availableSharedCapacity
             * @param space
             * @return
             */
            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
                    int available = availableSharedCapacity.get();
                    //如果剩余可用容量小于 LINK_CAPACITY,返回false
                    if (available < space) {
                        return false;
                    }
                    //调用availableSharedCapacity线程安全的CAS方法
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        /**
         * 头指针
         */
        private final Head head;
        /**
         * 尾指针
         */
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        /**
         * 指向下一个WeakOrderQueue
         */
        private WeakOrderQueue next;
        /**
         * 拥有者,干嘛的?? 要注意到这是一个弱引用,就是不会影响Thread对象的GC的,如果thread为空,owner.get()会返回null
         */
        private final WeakReference<Thread> owner;
        /**
         * WeakOrderQueue的唯一标记
         */
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        /**
         * 在Stack的io.netty.util.Recycler.Stack.pushLater()中如果没有WeakOrderQueue,会调用这里new一个
         * @param stack
         * @param thread
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            // 初始化头和尾指针,指向这个新创建的Link
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            // 表示当前的WeakOrderQueue是被哪个线程拥有的. 因为只有不同线程去回收对象才会进到这个方法,所以thread不是这stack对应的线程
            // 这里的WeakReference,对Thread是一个弱引用,所以Thread在没有强引用时就会被回收(线程也是可以回收的对象)
            owner = new WeakReference<Thread>(thread);
        }
        /**
         * stack.setHead(queue)必须在构造器外进行,防止对象溢出.
         */
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            // 这个stack,头指针指向 这个新创建的WeakOrderQueue
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            //先预约space容量
            //预约成功, 对当前stack创建一个新的WeakOrderQueue
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        void add(DefaultHandle<?> handle) {
            // 更新最近一次回收的id, 注意这里只更新了lastRecycledId, recycleId没有更新, 等到真正回收的时候,会改成一致的.
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                // 判断剩余空间是否足够
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();

                // tail这是一个自增的变量,每次tail.get()就表示放到末尾了
                writeIndex = tail.get();
            }
            // 把对应的handle引用放到末尾的数组里
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // todo 这个方法JDK注释比较少,还没看懂.后面可以写个demo测试下.
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            // readIndex指向当前读取的, tail.get()表示最大的值, 不相等代表还有待读取的数据.
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        /**
         * 把WeakOrderQueue里面暂存的对象,传输到对应的stack,主动去回收对象.
         */
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                // 扩容
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        // 前面的add方法只更新了lastRecycledId, transfer执行好了,需要更新recycleId一致,表示回收成功.
                        element.recycleId = element.lastRecycledId;
                        // recycleId=0才表示可回收的
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    // 成功了,就把WeakOrderQueue数组里置为空,释放对对象的引用
                    srcElems[i] = null;

                    //判断是否回收
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    // element是Link数组里的对象,stack指向目标stack
                    element.stack = dst;
                    // 目标Stack数组的尾部, 放入element
                    dstElems[newDstSize ++] = element;
                }

                // 如果head.next还有,就需要继续扩容
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    // 扩容
                    this.head.reclaimSpace(LINK_CAPACITY);
                    // 指向下一个,等待下一次循环继续上面的操作.transfer方法外层是被循环调用的.
                    this.head.link = head.next;
                }

                // 下次从这里开始读
                head.readIndex = srcEnd;
                // 如果相等则表示没有剩余空间了,返回false
                if (dst.size == newDstSize) {
                    return false;
                }
                // 目标数组size修改
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                // 目标仍然是满的,直接返回false,就不做回收动作
                return false;
            }
        }
    }

    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        /**
         * 使用最少的同步操作,并且可以全部回收.
         */
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /**
         * 持有线程的弱引用, 如果Stack没有被回收,那么Thread也不能被回收了,但是Stack没有强引用,在map中是弱引用,前面提到的.关于引用的知识回头再细化下.S
         */
        final WeakReference<Thread> threadRef;
        /**
         * 容量,用一个AtomicInteger表示,是为了可以并发CAS修改;
         */
        final AtomicInteger availableSharedCapacity;
        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        /**
         * 指向当前的WeakOrderQueue 和 前一个
         */
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            // maxSharedCapacityFactor默认为2
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            //取初始值和maxCapacity较小的一个; 如果INITIAL_CAPACITY < maxCapacity后面可以动态扩容
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        /**
         * 标记为同步,保证两个操作顺序执行
         * 重要的一点就是这个方法是 synchronized 的, 这个类里面唯一的synchronized方法
         */
        synchronized void setHead(WeakOrderQueue queue) {
            // synchronized避免并发修改queue.setNext的情况.
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                // 每次扩容两倍,直到newCapacity大于expectedCapacity
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // 这两个应该相等
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            //获取出的对象,置为0表示没有被回收
            ret.recycleId = 0;
            //获取出的对象,置为0表示没有被回收
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        // 尝试回收
        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            // 指向当前的指针
            WeakOrderQueue cursor = this.cursor;
            // 当前为null,就指向head,head也为null就跳出返回false
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                // 线程被回收了
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    // cursor.owner.get() == null表示,WeakOrderQueue的归属线程被回收了.
                    // 这里读取的是线程安全的变量,确认没有数据可回收了
                    // 第一个queue永远不回收,因为更新head指针会存在并发.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                //cursor.transfer(this)返回false,代表没有读取的数据了
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        // 这是一个单向链表,只要改变prev的引用,老的节点会被回收的.
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }
        /**
         * 会综合判断,如果是当前线程,直接放进数组中,如果不是,就先报错到WeakOrderQueue中.
         */
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                //保存到WeakOrderQueue,等待回收.
                pushLater(item, currentThread);
            }
        }

        /**
         * 立即push,把item对象回收到elements数组中
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            // 如果没回收,recycleId和lastRecycledId应该都是0; 正常应该不会进来, 感觉应该是作者为了在开发中排除这种情况.
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            // 都更新为OWN_THREAD_ID,表示被回收过了
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            // 如果size >= maxCapacity, 就会执行dropHandle()
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                // dropHandle()返回true,就直接return掉,本次不做回收.
                return;
            }
            // 如果size == elements.length,就要对elements数组进行扩容,每次扩容2倍,最大到maxCapacity
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            // 这里要注意: elements.length是数组的长度,包括空位; size是数组中有内容的长度, 这里是在最末尾放item;
            elements[size] = item;
            // size+1
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            // 为了在回收的过程中没有并发,如果回收的不是当前线程的Stack的对象,
            // 就放入到它的WeakOrderQueue,等它自己拿的时候回收,这样recycle方法就没有并发了;这种思想在Doug lea的AQS里也有.
            // 获取当前线程对应的Map<Stack<?>, WeakOrderQueue>
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            // 根据this Stack获取 WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            // 如果queue就需要创建一个
            if (queue == null) {
                // 大于上限,就放入一个DUMMY,表示满了
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // WeakOrderQueue.allocate方法,针对需要回收的这个Stack,创建一个新的WeakOrderQueue
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            // 判断是否已经回收
            if (!handle.hasBeenRecycled) {
                //handleRecycleCount初始为-1, ++handleRecycleCount = 0, 所以第一次肯定会进去.位运算的性能很好.
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    // ratioMask是一个掩码,解释见下方
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
/**
 dropHandle方法里有用到一个掩码ratioMask, 这个必须是2的次方-1,
 如果按默认值7 (“111”) , 只要低三位不全0, 就会返回true本次忽略不做回收. 所以返回true概率是 7/8, 目的是为了让回收动作缓慢一些, 内存池慢慢的增加, 减少对系统的压力. 不得不说作者考虑的真仔细.


 public class RecyclerTest {

 static class WrapRecycler {

 private List<String> list;

 private final static Recycler<WrapRecycler> RECYCLER = new Recycler<WrapRecycler>() {
@Override
protected WrapRecycler newObject(Handle<WrapRecycler> handle) {
return new WrapRecycler(handle);
}
};

 Recycler.Handle<WrapRecycler> handle;

 WrapRecycler(Recycler.Handle<WrapRecycler> handle) {
 this.handle = handle;
 this.list = new ArrayList<>(1000);
 }

 List<String> getList() {
 return list;
 }

 static WrapRecycler getInstance() {
 return RECYCLER.get();
 }

 void recycle() {
 handle.recycle(this);
 }
 }

 @Test
 public void testDifferentThreadRecycle() throws InterruptedException, IOException {
 System.out.println("Main thread started ...");
 final WrapRecycler instance = WrapRecycler.getInstance();
 instance.getList().add("111");      // main 线程放入一个字符串
 final CountDownLatch countDownLatch = new CountDownLatch(1);

 new Thread(new Runnable() {         // 这里新创建一个线程,在新的线程中可以回收main线程中的对象.
 @Override
 public void run() {
 System.out.println("Sub thread started ...");

 List<String> list = instance.getList();
 list.add("222");            // 子线程放入一个字符串.

 instance.recycle();         // 对main线程从对象池中回去的对象家进行回收动作.

 System.out.println("Sub Thread get list : " + WrapRecycler.getInstance().getList());    // 在子线程中从对象池获取对象
 countDownLatch.countDown();
 }
 }).start();

 countDownLatch.await();

 System.out.println("Main Thread get list : " + WrapRecycler.getInstance().getList());           // 在主线程中从对象池获取对象
 System.in.read();
 }
 }




















 */