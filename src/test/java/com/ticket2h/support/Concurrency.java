package com.ticket2h.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

public final class Concurrency {

    /**
     * Chạy {@code tasks} việc trên {@code threads} luồng, cố gắng cho các việc bắt đầu cùng lúc.
     * Ném lỗi nếu bất kỳ việc nào ném exception không được xử lý.
     *
     * <ul>
     *   <li>{@code ready}: chờ đủ luồng sẵn sàng; {@code start}: thả tất cả cùng lúc.</li>
     *   <li>{@code ready} đếm {@code min(tasks, threads)}: pool chỉ chạy được {@code threads} việc một
     *       lúc, đếm {@code tasks} (ví dụ 10.000) thì luồng chính chờ mãi.</li>
     *   <li>{@code future.get} bọc exception của task trong {@code ExecutionException}.</li>
     * </ul>
     */
    public static <T> List<T> run(int tasks, int threads, IntFunction<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(Math.min(tasks, threads));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>(tasks);
            for (Future<T> future : futures) {
                results.add(future.get(2, TimeUnit.MINUTES));
            }
            return results;
        }
    }

    private Concurrency() {}
}
