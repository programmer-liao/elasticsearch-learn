/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.util.concurrent;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.node.Node;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class EsExecutors {

    private static final DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(EsExecutors.class);

    // although the available processors may technically change, for node sizing we use the number available at launch
    private static final int MAX_NUM_PROCESSORS = Runtime.getRuntime().availableProcessors();

    /**
     * Setting to manually set the number of available processors. This setting is used to adjust thread pool sizes per node.
     */
    public static final Setting<Integer> PROCESSORS_SETTING = new Setting<>(
        "processors",
        s -> Integer.toString(MAX_NUM_PROCESSORS),
        processorsParser("processors"),
        Property.Deprecated,
        Property.NodeScope
    );

    /**
     * Setting to manually control the number of allocated processors. This setting is used to adjust thread pool sizes per node. The
     * default value is {@link Runtime#availableProcessors()} but should be manually controlled if not all processors on the machine are
     * available to Elasticsearch (e.g., because of CPU limits).
     */
    public static final Setting<Integer> NODE_PROCESSORS_SETTING = new Setting<>(
        "node.processors",
        PROCESSORS_SETTING,
        processorsParser("node.processors"),
        Property.NodeScope
    );

    private static Function<String, Integer> processorsParser(final String name) {
        return s -> {
            final int value = Setting.parseInt(s, 1, name);
            final int availableProcessors = MAX_NUM_PROCESSORS;
            if (value > availableProcessors) {
                deprecationLogger.critical(
                    DeprecationCategory.SETTINGS,
                    "processors",
                    "setting [{}] to value [{}] which is more than available processors [{}] is deprecated",
                    name,
                    value,
                    availableProcessors
                );
            }
            return value;
        };
    }

    /**
     * Returns the number of allocated processors. Defaults to {@link Runtime#availableProcessors()} but can be overridden by passing a
     * {@link Settings} instance with the key {@code node.processors} set to the desired value.
     *
     * @param settings a {@link Settings} instance from which to derive the allocated processors
     * @return the number of allocated processors
     */
    public static int allocatedProcessors(final Settings settings) {
        return NODE_PROCESSORS_SETTING.get(settings);
    }

    public static PrioritizedEsThreadPoolExecutor newSinglePrioritizing(
        String name,
        ThreadFactory threadFactory,
        ThreadContext contextHolder,
        ScheduledExecutorService timer,
        PrioritizedEsThreadPoolExecutor.StarvationWatcher starvationWatcher
    ) {
        return new PrioritizedEsThreadPoolExecutor(
            name,
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            threadFactory,
            contextHolder,
            timer,
            starvationWatcher
        );
    }

    public static EsThreadPoolExecutor newScaling(
        String name,
        int min,
        int max,
        long keepAliveTime,
        TimeUnit unit,
        boolean rejectAfterShutdown,
        ThreadFactory threadFactory,
        ThreadContext contextHolder
    ) {
        ExecutorScalingQueue<Runnable> queue = new ExecutorScalingQueue<>();
        EsThreadPoolExecutor executor = new EsThreadPoolExecutor(
            name,
            min,
            max,
            keepAliveTime,
            unit,
            queue,
            threadFactory,
            new ForceQueuePolicy(rejectAfterShutdown),
            contextHolder
        );
        queue.executor = executor;
        return executor;
    }

    public static EsThreadPoolExecutor newFixed(
        String name,
        int size,
        int queueCapacity,
        ThreadFactory threadFactory,
        ThreadContext contextHolder
    ) {
        BlockingQueue<Runnable> queue;
        if (queueCapacity < 0) {
            queue = ConcurrentCollections.newBlockingQueue();
        } else {
            queue = new SizeBlockingQueue<>(ConcurrentCollections.<Runnable>newBlockingQueue(), queueCapacity);
        }
        return new EsThreadPoolExecutor(
            name,
            size,
            size,
            0,
            TimeUnit.MILLISECONDS,
            queue,
            threadFactory,
            new EsAbortPolicy(),
            contextHolder
        );
    }

    /**
     * Return a new executor that will automatically adjust the queue size based on queue throughput.
     *
     * @param size number of fixed threads to use for executing tasks
     * @param initialQueueCapacity initial size of the executor queue
     * @param minQueueSize minimum queue size that the queue can be adjusted to
     * @param maxQueueSize maximum queue size that the queue can be adjusted to
     * @param frameSize number of tasks during which stats are collected before adjusting queue size
     */
    public static EsThreadPoolExecutor newAutoQueueFixed(
        String name,
        int size,
        int initialQueueCapacity,
        int minQueueSize,
        int maxQueueSize,
        int frameSize,
        TimeValue targetedResponseTime,
        ThreadFactory threadFactory,
        ThreadContext contextHolder
    ) {
        if (initialQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                "initial queue capacity for [" + name + "] executor must be positive, got: " + initialQueueCapacity
            );
        }
        ResizableBlockingQueue<Runnable> queue = new ResizableBlockingQueue<>(
            ConcurrentCollections.<Runnable>newBlockingQueue(),
            initialQueueCapacity
        );
        return new QueueResizingEsThreadPoolExecutor(
            name,
            size,
            size,
            0,
            TimeUnit.MILLISECONDS,
            queue,
            minQueueSize,
            maxQueueSize,
            TimedRunnable::new,
            frameSize,
            targetedResponseTime,
            threadFactory,
            new EsAbortPolicy(),
            contextHolder
        );
    }

    /**
     * Checks if the runnable arose from asynchronous submission of a task to an executor. If an uncaught exception was thrown
     * during the execution of this task, we need to inspect this runnable and see if it is an error that should be propagated
     * to the uncaught exception handler.
     *
     * @param runnable the runnable to inspect, should be a RunnableFuture
     * @return non fatal exception or null if no exception.
     */
    public static Throwable rethrowErrors(Runnable runnable) {
        if (runnable instanceof RunnableFuture) {
            assert ((RunnableFuture) runnable).isDone();
            try {
                ((RunnableFuture) runnable).get();
            } catch (final Exception e) {
                /*
                 * In theory, Future#get can only throw a cancellation exception, an interrupted exception, or an execution
                 * exception. We want to ignore cancellation exceptions, restore the interrupt status on interrupted exceptions, and
                 * inspect the cause of an execution. We are going to be extra paranoid here though and completely unwrap the
                 * exception to ensure that there is not a buried error anywhere. We assume that a general exception has been
                 * handled by the executed task or the task submitter.
                 */
                assert e instanceof CancellationException || e instanceof InterruptedException || e instanceof ExecutionException : e;
                final Optional<Error> maybeError = ExceptionsHelper.maybeError(e);
                if (maybeError.isPresent()) {
                    // throw this error where it will propagate to the uncaught exception handler
                    throw maybeError.get();
                }
                if (e instanceof InterruptedException) {
                    // restore the interrupt status
                    Thread.currentThread().interrupt();
                }
                if (e instanceof ExecutionException) {
                    return e.getCause();
                }
            }
        }

        return null;
    }

    private static final class DirectExecutorService extends AbstractExecutorService {

        @SuppressForbidden(reason = "properly rethrowing errors, see EsExecutors.rethrowErrors")
        DirectExecutorService() {
            super();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            command.run();
            rethrowErrors(command);
        }
    }

    /**
     * {@link ExecutorService} that executes submitted tasks on the current thread. This executor service does not support being
     * shutdown.
     */
    public static final ExecutorService DIRECT_EXECUTOR_SERVICE = new DirectExecutorService();

    public static String threadName(Settings settings, String namePrefix) {
        if (Node.NODE_NAME_SETTING.exists(settings)) {
            return threadName(Node.NODE_NAME_SETTING.get(settings), namePrefix);
        } else {
            // TODO this should only be allowed in tests
            return threadName("", namePrefix);
        }
    }

    public static String threadName(final String nodeName, final String namePrefix) {
        // TODO missing node names should only be allowed in tests
        return "elasticsearch" + (nodeName.isEmpty() ? "" : "[") + nodeName + (nodeName.isEmpty() ? "" : "]") + "[" + namePrefix + "]";
    }

    public static ThreadFactory daemonThreadFactory(Settings settings, String namePrefix) {
        return daemonThreadFactory(threadName(settings, namePrefix));
    }

    public static ThreadFactory daemonThreadFactory(String nodeName, String namePrefix) {
        assert nodeName != null && false == nodeName.isEmpty();
        return daemonThreadFactory(threadName(nodeName, namePrefix));
    }

    public static ThreadFactory daemonThreadFactory(String namePrefix) {
        return new EsThreadFactory(namePrefix);
    }

    static class EsThreadFactory implements ThreadFactory {

        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        EsThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            return AccessController.doPrivileged((PrivilegedAction<Thread>) () -> {
                Thread t = new Thread(group, r, namePrefix + "[T#" + threadNumber.getAndIncrement() + "]", 0);
                t.setDaemon(true);
                return t;
            });
        }

    }

    /**
     * Cannot instantiate.
     */
    private EsExecutors() {}

    static class ExecutorScalingQueue<E> extends LinkedTransferQueue<E> {

        ThreadPoolExecutor executor;

        ExecutorScalingQueue() {}

        @Override
        public boolean offer(E e) {
            // first try to transfer to a waiting worker thread
            if (tryTransfer(e) == false) {
                // check if there might be spare capacity in the thread
                // pool executor
                int left = executor.getMaximumPoolSize() - executor.getCorePoolSize();
                if (left > 0) {
                    // reject queuing the task to force the thread pool
                    // executor to add a worker if it can; combined
                    // with ForceQueuePolicy, this causes the thread
                    // pool to always scale up to max pool size and we
                    // only queue when there is no spare capacity
                    return false;
                } else {
                    return super.offer(e);
                }
            } else {
                return true;
            }
        }

    }

    /**
     * A handler for rejected tasks that adds the specified element to this queue,
     * waiting if necessary for space to become available.
     */
    static class ForceQueuePolicy extends EsRejectedExecutionHandler {

        /**
         * This flag is used to indicate if {@link Runnable} should be rejected once the thread pool is shutting down, ie once
         * {@link ThreadPoolExecutor#shutdown()} has been called. Scaling thread pools are expected to always handle tasks rejections, even
         * after shutdown or termination, but it's not the case of all existing thread pools so this flag allows to keep the previous
         * behavior.
         */
        private final boolean rejectAfterShutdown;

        /**
         * @param rejectAfterShutdown indicates if {@link Runnable} should be rejected once the thread pool is shutting down
         */
        ForceQueuePolicy(boolean rejectAfterShutdown) {
            this.rejectAfterShutdown = rejectAfterShutdown;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (rejectAfterShutdown) {
                if (executor.isShutdown()) {
                    reject(executor, task);
                } else {
                    put(executor, task);
                    // we need to check again the executor state as it might have been concurrently shut down; in this case
                    // the executor's workers are shutting down and might have already picked up the task for execution.
                    if (executor.isShutdown() && executor.remove(task)) {
                        reject(executor, task);
                    }
                }
            } else {
                put(executor, task);
            }
        }

        private void put(ThreadPoolExecutor executor, Runnable task) {
            final BlockingQueue<Runnable> queue = executor.getQueue();
            // force queue policy should only be used with a scaling queue
            assert queue instanceof ExecutorScalingQueue;
            try {
                queue.put(task);
            } catch (final InterruptedException e) {
                assert false : "a scaling queue never blocks so a put to it can never be interrupted";
                throw new AssertionError(e);
            }
        }

        private void reject(ThreadPoolExecutor executor, Runnable task) {
            incrementRejections();
            throw newRejectedException(task, executor, true);
        }
    }

}
