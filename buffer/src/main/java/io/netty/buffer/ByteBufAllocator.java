/*
 * Copyright 2012 The Netty Project
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
package io.netty.buffer;

/**
 * 负责实现分配缓冲区。这个接口的实现应该是线程安全的。
 * Implementations are responsible to allocate buffers. Implementations of this interface are expected to be
 * thread-safe.
 */
public interface ByteBufAllocator {

    ByteBufAllocator DEFAULT = ByteBufUtil.DEFAULT_ALLOCATOR;

    /**
     * 分配一个{@link ByteBuf}
     *
     * Allocate a {@link ByteBuf}. If it is a direct or heap buffer
     * depends on the actual implementation.
     * @return ByteBuf
     */
    ByteBuf buffer();

    /**
     * 使用给定的容量分配{@link ByteBuf}
     *
     * Allocate a {@link ByteBuf} with the given initial capacity.
     * If it is a direct or heap buffer depends on the actual implementation.
     * @param initialCapacity
     * @return ByteBuf
     */
    ByteBuf buffer(int initialCapacity);

    /**
     * 分配具有给定初始容量和给定最大容量的{@link ByteBuf}
     *
     * Allocate a {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity. If it is a direct or heap buffer depends on the actual
     * implementation.
     * @param initialCapacity
     * @param maxCapacity
     * @return ByteBuf
     */
    ByteBuf buffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个{@link ByteBuf}，最好是一个适合I/O的直接缓冲区
     *
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     * @return ByteBuf
     */
    ByteBuf ioBuffer();

    /**
     * 分配一个{@link ByteBuf}，最好是一个适合I/O的直接缓冲区
     *
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     * @param initialCapacity
     * @return ByteBuf
     */
    ByteBuf ioBuffer(int initialCapacity);

    /**
     * 分配一个{@link ByteBuf}，最好是一个适合I/O的直接缓冲区
     *
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     * @param initialCapacity
     * @param maxCapacity
     * @return ByteBuf
     */
    ByteBuf ioBuffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个堆缓冲区的 {@link ByteBuf}.
     *
     * Allocate a heap {@link ByteBuf}.
     * @return ByteBuf
     */
    ByteBuf heapBuffer();

    /**
     * 分配一个具有给定初始容量的堆{@link ByteBuf}。
     *
     * Allocate a heap {@link ByteBuf} with the given initial capacity.
     * @param initialCapacity
     * @return ByteBuf
     */
    ByteBuf heapBuffer(int initialCapacity);

    /**
     * 分配一个具有给定初始容量和给定初始容量的堆{@link ByteBuf}
     *
     * Allocate a heap {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity.
     * @param initialCapacity
     * @param maxCapacity
     * @return ByteBuf
     */
    ByteBuf heapBuffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个直接缓冲区 {@link ByteBuf}.
     *
     * Allocate a direct {@link ByteBuf}.
     * @return ByteBuf
     */
    ByteBuf directBuffer();

    /**
     * 分配一个具有给定初始容量的直接{@link ByteBuf}。
     *
     * Allocate a direct {@link ByteBuf} with the given initial capacity.
     * @param initialCapacity
     * @return ByteBuf
     */
    ByteBuf directBuffer(int initialCapacity);

    /**
     * 分配具有给定初始容量和给定最大容量的直接{@link ByteBuf}。
     *
     * Allocate a direct {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity.
     */
    ByteBuf directBuffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个{@link CompositeByteBuf}。
     *
     * Allocate a {@link CompositeByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation.
     */
    CompositeByteBuf compositeBuffer();

    /**
     * 分配一个{@link CompositeByteBuf}，其中包含给定的可以存储在其中的组件的最大数量。
     *
     * Allocate a {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     * If it is a direct or heap buffer depends on the actual implementation.
     */
    CompositeByteBuf compositeBuffer(int maxNumComponents);

    /**
     * 分配一个堆 {@link CompositeByteBuf}.
     *
     * Allocate a heap {@link CompositeByteBuf}.
     */
    CompositeByteBuf compositeHeapBuffer();

    /**
     * 分配一个堆{@link CompositeByteBuf}，其中包含给定的可以存储在其中的组件的最大数量。
     *
     * Allocate a heap {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     */
    CompositeByteBuf compositeHeapBuffer(int maxNumComponents);

    /**
     * 分配一个直接@link CompositeByteBuf}
     *
     * Allocate a direct {@link CompositeByteBuf}.
     */
    CompositeByteBuf compositeDirectBuffer();

    /**
     * 分配一个直接{@link CompositeByteBuf}，其中包含给定的可以存储在其中的组件的最大数量。
     *
     * Allocate a direct {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     */
    CompositeByteBuf compositeDirectBuffer(int maxNumComponents);

    /**
     * 如果直接{@link ByteBuf}的值被合并，则返回{@code true}
     *
     * Returns {@code true} if direct {@link ByteBuf}'s are pooled
     */
    boolean isDirectBufferPooled();

    /**
     * 计算一个{@link ByteBuf}的新容量，当一个{@link ByteBuf}需要由{@code minNewCapacity}展开，
     * 而{@code maxCapacity}为上限时，将使用这个{@link ByteBuf}。
     *
     * Calculate the new capacity of a {@link ByteBuf} that is used when a {@link ByteBuf} needs to expand by the
     * {@code minNewCapacity} with {@code maxCapacity} as upper-bound.
     */
    int calculateNewCapacity(int minNewCapacity, int maxCapacity);

}
