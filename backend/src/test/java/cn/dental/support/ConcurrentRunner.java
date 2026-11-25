package cn.dental.support;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class ConcurrentRunner {
    private ConcurrentRunner() {}
    public static <T> List<T> run(int count, Duration timeout, Callable<T> task) throws Exception {
        if (count < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException();
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(count);
        List<Future<T>> futures = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            for (int i=0; i<count; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new TimeoutException();
                return task.call();
            }));
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new TimeoutException();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(Math.max(1, deadline-System.nanoTime()), TimeUnit.NANOSECONDS));
            return results;
        } finally {
            start.countDown(); futures.forEach(f -> f.cancel(true)); pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test workers did not terminate");
        }
    }
}
