# Potential problem with ThreadLocal in Java 17+

## The problem
In Java 17, a security fix was applied. All tasks that are executed within ForkJoinPool.commonPool will
clear all ThreadLocal data for that thread after the task is completed.

The rationale is that since ForkJoinPool.commonPool is a shared resource for all code that runs in the same JVM,
it may be harmful to let them snoop on each others thread local data.

This security fix has the side effect that all libraries that utilize ThreadLocal values for performance purposes
(support reuse of expensive objects) could now get a drastic difference in performance.

I created a small [runner](src/main/java/Main.java) to illustrate this behavior:

Running it with Java 11 gives:

    Version: 11.0.3+12-b304.39
    Num runs: 100000
    Num hits: 99985
    Diff: 15
    Expected diff = num threads in FJP

Running it with Java 17 gives:

    $ java Main
    Version: 17.0.3+6-LTS
    Num runs: 100000
    Num hits: 84135
    Diff: 15865
    Expected diff = num threads in FJP

Is ThreadLocalRandom affected by this? Fortunately it is not. You could perhaps assume that this is implemented as
ThreadLocal<Random> but it is instead implemented using some special fields inside the Thread object which are not
cleared upon task completion.

This is the issue reported to the JDK bug system: [JDK-8285638](https://bugs.openjdk.java.net/browse/JDK-8285638)

## Known affected libraries

This performance problem could affect lot of libraries, including some very commonly used ones.

### logstash

This issue illustrates a commonly used pattern for using ThreadLocal to reuse expensive objects and how it can go wrong 
https://github.com/logfellow/logstash-logback-encoder/issues/722

### Google grpc-java
This one is probably safe as it is and fits well together with the intended purpose of the security fix:
https://github.com/grpc/grpc-java/blob/d4fa0ecc07495097453b0a2848765f076b9e714c/context/src/main/java/io/grpc/ThreadLocalContextStorage.java#L32


### Google Guava

Correctness:
https://github.com/google/guava/blob/ecbbcc5fc97eeb7384c18b4a2666e7b531e38304/guava/src/com/google/common/eventbus/Dispatcher.java#L79
https://github.com/google/guava/blob/ecbbcc5fc97eeb7384c18b4a2666e7b531e38304/guava/src/com/google/common/eventbus/Dispatcher.java#L88


### Google protobuf

Performance optimizations:
https://github.com/protocolbuffers/protobuf/blob/520c601c99012101c816b6ccc89e8d6fc28fdbb8/java/core/src/main/java/com/google/protobuf/ByteBufferWriter.java#L71
https://github.com/protocolbuffers/protobuf/blob/520c601c99012101c816b6ccc89e8d6fc28fdbb8/java/util/src/main/java/com/google/protobuf/util/Timestamps.java#L86


## What should we do?

We can't stay on Java 11 forever, so we need to make sure all libraries that we use are will continue to perform
as expected on Java 17+

## Proposed workarounds

We can start by adding runtime instrumentation to detect when there is a problem with ThreadLocal usage.
If we count the number of calls to ThreadLocal.initialValue() we can perhaps identify when there's a problem.

Perhaps we can also use runtime instrumentation to change the behavior of the common pool or the ThreadLocal implementation?
This is probably not a good idea, since it would directly counteract the security fix.

Instead, it is probably better to operate on a case by case basis and fix the affected libraries.
Exactly how to do that is probably best done as a community collaborative exercise.

I have added some ideas to this repository:
* [ThreadLocalWithFallback](src/main/java/ThreadLocalWithFallback.java)
* [ConcurrentThreadLocalWithFallback](src/main/java/ConcurrentThreadLocalWithFallback.java)
