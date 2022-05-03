import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
  private final static int NUM_RUNS = 100000;

  public static void main(String[] args) throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(NUM_RUNS);
    final AtomicInteger hits = new AtomicInteger();
    final ThreadLocal<Object> threadLocal = new ThreadLocal<>();
    final Object ref = new Object();

    final ArrayList<ForkJoinTask<String>> objects = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final ForkJoinTask<String> submit = ForkJoinPool.commonPool().submit(() -> Thread.currentThread().getId() + "");
      objects.add(submit);
    }
    objects.forEach(task -> System.out.println(task.join()));
    for (int i = 0; i < NUM_RUNS; i++) {
      ForkJoinPool.commonPool().execute(() -> {
        final Object value = threadLocal.get();
        if (value == null) {
          threadLocal.set(ref);
        } else if (value == ref){
          hits.incrementAndGet();
        } else {
          System.err.println("Error, unexpected value");
        }
        latch.countDown();
      });
    }

    latch.await();
    System.out.println("Version: " + Runtime.version());
    System.out.println("Num runs: " + NUM_RUNS);
    System.out.println("Num hits: " + hits.get());
    System.out.println("Diff: " + (NUM_RUNS - hits.get()));
    System.out.println("Expected diff = num threads in ForkJoinPool.commonPool()");
  }
}
