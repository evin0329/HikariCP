/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zaxxer.hikari.util;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;

import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_RESERVED;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
{
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

   private final QueuedSequenceSynchronizer synchronizer;
   private final CopyOnWriteArrayList<T> sharedList;
   private final boolean weakThreadLocals;

   private final ThreadLocal<List<Object>> threadList;
   private final IBagStateListener listener;
   private final AtomicInteger waiters;
   private volatile boolean closed;

   public interface IConcurrentBagEntry
   {
      int STATE_NOT_IN_USE = 0;
      int STATE_IN_USE = 1;
      int STATE_REMOVED = -1;
      int STATE_RESERVED = -2;

      boolean compareAndSet(int expectState, int newState);
      void lazySet(int newState);
      int getState();
   }

   public interface IBagStateListener
   {
      Future<Boolean> addBagItem();
   }

   /**
    * 使用指定的监听器构造一个 ConcurrentBag。
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener)
   {
      this.listener = listener;
      this.weakThreadLocals = useWeakThreadLocals();

      this.waiters = new AtomicInteger();
      this.sharedList = new CopyOnWriteArrayList<>();
      this.synchronizer = new QueuedSequenceSynchronizer();
      if (weakThreadLocals) {
         this.threadList = new ThreadLocal<>();
      }
      else {
         this.threadList = new ThreadLocal<List<Object>>() {
            @Override
            protected List<Object> initialValue()
            {
               return new FastList<>(IConcurrentBagEntry.class, 16);
            }
         };
      }
   }

   /**
    * 该方法将从包中借用一个 BagEntry，如果没有可用的则阻塞指定的超时时间。
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    *
    * @param timeout how long to wait before giving up, in units of unit(放弃前等待多长时间，以单位为单位)
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs(如果发生超时，则从包中借用实例或 null)
    * @throws InterruptedException if interrupted while waiting
    */
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      // 首先尝试线程本地列表
      // Try the thread-local list first
      List<Object> list = threadList.get();
      if (weakThreadLocals && list == null) {
         list = new ArrayList<>(16);
         threadList.set(list);
      }

      for (int i = list.size() - 1; i >= 0; i--) {
         final Object entry = list.remove(i);
         @SuppressWarnings("unchecked")
         final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry;
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }

      // 否则，扫描共享列表...以获得最大超时时间
      // Otherwise, scan the shared list ... for maximum of timeout
      timeout = timeUnit.toNanos(timeout);
      Future<Boolean> addItemFuture = null;
      final long startScan = System.nanoTime();
      final long originTimeout = timeout;
      long startSeq;
      waiters.incrementAndGet();
      try {
         do {
            // scan the shared list
            do {
               startSeq = synchronizer.currentSequence();
               // 当无可用本地化资源时，遍历全部资源，查看是否存在可用资源
               // 因此被一个线程本地化的资源也可能被另一个线程“窃取”
               for (T bagEntry : sharedList) {
                  // 如果状态未使用
                  if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                     // 如果我们可能窃取了另一个线程的新连接，请重新启动添加...
                     // if we might have stolen another thread's new connection, restart the add...
                     if (waiters.get() > 1 && addItemFuture == null) {
                        listener.addBagItem();
                     }

                     return bagEntry;
                  }
               }
            } while (startSeq < synchronizer.currentSequence());

            if (addItemFuture == null || addItemFuture.isDone()) {
               addItemFuture = listener.addBagItem();
            }

            timeout = originTimeout - (System.nanoTime() - startScan);
         } while (timeout > 10_000L && synchronizer.waitUntilSequenceExceeded(startSeq, timeout)); // 未超时并且等待直到超过序列
      }
      finally {
         waiters.decrementAndGet();
      }

      return null;
   }

   /**
    * 这个方法将一个借用的对象返回给包。 从包中借用但从未“归还”的对象将导致内存泄漏。
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    *
    * @param bagEntry the value to return to the bag(返回包中的值)
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the requited value was not borrowed from the bag
    */
   public void requite(final T bagEntry)
   {
      bagEntry.lazySet(STATE_NOT_IN_USE);

      final List<Object> threadLocalList = threadList.get();
      if (threadLocalList != null) {
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
      }

      // 唤醒
      synchronizer.signal();
   }

   /**
    * 将新物品添加到包中以供他人借用。
    * Add a new object to the bag for others to borrow.
    *
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);
      synchronizer.signal();
   }

   /**
    * 从包中取出一个值。此方法只能使用通过 <code>borrow(long, TimeUnit)<code> 或 <code>reserve(T)<code> 获得的对象调用
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    *
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry)
   {
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }

      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }

      // synchronizer.signal();
      return removed;
   }

   /**
    * 关闭袋子以进一步添加。
    * Close the bag to further adds.
    */
   @Override
   public void close()
   {
      closed = true;
   }

   /**
    * 此方法提供包中 BagEntry 项目处于指定状态的时间“快照”。它不会以任何方式“锁定”或保留项目。在对列表中的项目执行任何操作之前，对它们调用 <code>reserve(T)<code>。
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state)
   {
      final ArrayList<T> list = new ArrayList<>(sharedList.size());
      for (final T entry : sharedList) {
         if (entry.getState() == state) {
            list.add(entry);
         }
      }

      return list;
   }

   /**
    * 该方法提供了袋子物品的时间“快照”。它不会以任何方式“锁定”或保留项目。在对列表中的项目执行任何操作之前，对列表中的项目调用 <code>reserve(T)<code>，或了解修改项目的并发影响。
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    */
   @SuppressWarnings("unchecked")
   public List<T> values()
   {
      return (List<T>) sharedList.clone();
   }

   /**
    * 该方法用于使包中的物品“不可借”。它主要用于对 <code>values(int)<code> 方法返回的项目进行操作时。
    * 保留的物品可以通过 <code>remove(T)<code> 从包中取出，无需取消保留。通过调用 <code>unreserve(T)<code> 方法，可以再次借出没有从包中取出的物品。
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * 此方法用于使通过 <code>reserve(T)<code> 保留的项目再次可供借用。
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    */
   public void unreserve(final T bagEntry)
   {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         synchronizer.signal();
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * 获取等待（等待）包中项目可用的线程数。
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    */
   public int getPendingQueue()
   {
      return synchronizer.getQueueLength();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state)
   {
      int count = 0;
      for (final T entry : sharedList) {
         if (entry.getState() == state) {
            count++;
         }
      }
      return count;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag
    */
   public int size()
   {
      return sharedList.size();
   }

   public void dumpState()
   {
      for (T bagEntry : sharedList) {
         LOGGER.info(bagEntry.toString());
      }
   }

   /**
    * 根据该类和系统类加载器之间是否存在自定义的类加载器实现来确定是否使用 WeakReferences。
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    *
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    */
   private boolean useWeakThreadLocals()
   {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      }
      catch (SecurityException se) {
         return true;
      }
   }
}
