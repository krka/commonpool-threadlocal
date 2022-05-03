import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
public class ThreadLocalWithFallback<T> extends ThreadLocal<T> {
  private final Map<Long, T> fallback = new ConcurrentHashMap<>();
  private final Supplier<? extends T> supplier;
  private final Consumer<? super T> closer;

  private final ReferenceQueue<Thread> deadThreads = new ReferenceQueue<>();
  // Used to avoid creating more weak references than necessary
  private final Map<Long, RefWithThreadId> activeThreads = new ConcurrentHashMap<>();

  private ThreadLocalWithFallback(Supplier<? extends T> supplier, Consumer<? super T> closer) {
    this.supplier = supplier;
    this.closer = closer;
  }

  @Override
  protected T initialValue() {
    final Thread thread = Thread.currentThread();
    final long threadId = thread.getId();
    final T fallbackValue = fallback.get(threadId);
    if (fallbackValue != null) {
      // This case is typically only reachable on Java 17+ using ForkJoinPool.commonPool()
      return fallbackValue;
    }

    final T value = Objects.requireNonNull(supplier.get());
    setFallback(value, thread, threadId);
    return value;
  }

  private void setFallback(T value, Thread thread, long threadId) {
    final T prevValue = fallback.put(threadId, value);
    if (prevValue == null) {
      if (!activeThreads.containsKey(threadId)) {
        // New thread - register for death and clean up any dead threads
        cleanupDeadThreads();
        activeThreads.put(threadId, new RefWithThreadId(thread, threadId, deadThreads));
      }
    }
  }

  public static <S> ThreadLocalWithFallback<S> withInitial(Supplier<? extends S> supplier) {
    return withInitial(supplier, obj -> {});
  }

  public static <S> ThreadLocalWithFallback<S> withInitial(Supplier<? extends S> supplier, Consumer<? super S> closer) {
    return new ThreadLocalWithFallback<>(supplier, closer);
  }

  @Override
  public void set(T value) {
    final Thread thread = Thread.currentThread();
    final long threadId = thread.getId();
    setFallback(value, thread, threadId);
    super.set(value);
  }

  @Override
  public void remove() {
    fallback.remove(Thread.currentThread().getId());
    super.remove();
  }

  protected void cleanupDeadThreads() {
    RefWithThreadId ref = (RefWithThreadId) deadThreads.poll();
    while (ref != null) {
      final T value = fallback.remove(ref.threadId);
      activeThreads.remove(ref.threadId);
      if (value != null) {
        try {
          closer.accept(value);
        } catch (Exception e) {
          // Ignore exceptions, we can't crash the current thread
        }
      }
      ref = (RefWithThreadId) deadThreads.poll();
    }
  }

  int fallbackSize() {
    return fallback.size();
  }

  private static class RefWithThreadId extends PhantomReference<Thread> {
    private final long threadId;

    public RefWithThreadId(Thread thread, long threadId, ReferenceQueue<? super Thread> q) {
      super(thread, q);
      this.threadId = threadId;
    }
  }
}
