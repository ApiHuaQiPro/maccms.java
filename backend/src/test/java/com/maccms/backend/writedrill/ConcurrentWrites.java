package com.maccms.backend.writedrill;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * 并发写测试支撑（B07，路线图第 5.3 节"写库批次另加：并发写测试"）：{@code threads} 个线程先全部就绪，再由同一个起跑信号同时执行
 * {@code task(i)}，收集每个线程的返回值或异常。写库批次用它覆盖每一个"先查后写"与条件写入：断言恰好几个成功、最终值是否正确、
 * 有没有越界（负余额、重复记录）。
 */
public final class ConcurrentWrites {

    /** 线程 i 的结果：{@code value} 或 {@code failure} 二者之一。 */
    public record Result<T>(int index, T value, Throwable failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    /** 全部线程的结果（按线程序号）。 */
    public record Outcome<T>(List<Result<T>> results) {

        public List<T> values() {
            return results.stream().filter(Result::succeeded).map(Result::value).toList();
        }

        public List<Throwable> failures() {
            return results.stream().filter(r -> !r.succeeded()).map(Result::failure).toList();
        }

        public long count(T value) {
            return values().stream().filter(v -> java.util.Objects.equals(v, value)).count();
        }
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private ConcurrentWrites() {
    }

    public static <T> Outcome<T> run(int threads, IntFunction<T> task) {
        return run(threads, task, DEFAULT_TIMEOUT);
    }

    public static <T> Outcome<T> run(int threads, IntFunction<T> task, Duration timeout) {
        if (threads < 2) {
            throw new IllegalArgumentException("并发测试至少 2 个线程");
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(index);
                }));
            }
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("线程未能全部就绪");
            }
            go.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            List<Result<T>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                try {
                    long left = Math.max(1, deadline - System.nanoTime());
                    results.add(new Result<>(i, futures.get(i).get(left, TimeUnit.NANOSECONDS), null));
                } catch (ExecutionException e) {
                    results.add(new Result<>(i, null, e.getCause()));
                } catch (TimeoutException e) {
                    throw new IllegalStateException("并发任务超时", e);
                }
            }
            return new Outcome<>(List.copyOf(results));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
