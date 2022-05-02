import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Since Java 17, using ThreadLocal may not behave as expected
 * when being called from ForkJoinPool.commonPool().
 * The common pool will clear all values for ThreadLocal after finishing a task,
 * so it can not be used for retaining expensive objects on the same thread across
 * multiple tasks.
 *
 * This subclass will instead let values survive by using a ConcurrentHashMap
 * based on Thread Id which is stable as long as the thread lives. This map is also cleaned up by
 * detecting thread death using a WeakReference.
 */
public class ConcurrentThreadLocalWithFallback<T> extends ThreadLocal<T> {
  private final Map<Long, T> fallback = new ConcurrentHashMap<>();
  private final Supplier<? extends T> supplier;
  private final ReferenceQueue<Thread> deadThreads = new ReferenceQueue<>();

  private ConcurrentThreadLocalWithFallback(Supplier<? extends T> supplier) {
    this.supplier = supplier;
  }

  @Override
  protected T initialValue() {
    processDeadThreads();
    final Thread currentThread = Thread.currentThread();
    long threadId = currentThread.getId();
    final T fallbackValue = fallback.get(threadId);
    if (fallbackValue != null) {
      return fallbackValue;
    }

    final T value = Objects.requireNonNull(supplier.get());
    set(value);
    return value;
  }

  public static <S> ConcurrentThreadLocalWithFallback<S> withInitial(Supplier<? extends S> supplier) {
    return new ConcurrentThreadLocalWithFallback<>(supplier);
  }

  @Override
  public void set(T value) {
    processDeadThreads();
    final Thread thread = Thread.currentThread();
    final long threadId = thread.getId();
    final T prevValue = fallback.put(threadId, value);
    if (prevValue == null) {
      // New thread - register for death
      final RefWithThreadId ref = new RefWithThreadId(thread, threadId, deadThreads);
    }
    super.set(value);
  }

  @Override
  public void remove() {
    fallback.remove(Thread.currentThread().getId());
    super.remove();
  }

  private void processDeadThreads() {
    while (true) {
      final RefWithThreadId ref = (RefWithThreadId) deadThreads.poll();
      if (ref == null) {
        break;
      }
      fallback.remove(ref.threadId);
    }
  }

  private static class RefWithThreadId extends WeakReference<Thread> {
    private final long threadId;

    public RefWithThreadId(Thread thread, long threadId, ReferenceQueue<? super Thread> q) {
      super(thread, q);
      this.threadId = threadId;
    }
  }
}
