# Stage 1 — Atomicity — LEARNINGS.md

## What I Built
Three counter implementations, each fixing Stage 0's `count++` race a
different way:
- `JobCounterSynchronized` — `synchronized` method around `count++`
- `JobCounterAtomicInt` — `AtomicInteger.incrementAndGet()`
- `JobCounterLongAdder` — `LongAdder.increment()`

---

## Test Methodology — The Mistake I Made First

My first test reused the Stage 0 producer/consumer harness (queue +
counter together) to validate the counter fix. This was wrong, and it
cost real debugging time.

### What happened
```
Jobs actually in queue after producers finished: 3130
Expected : 10000
Actual   : 22
Lost     : 9978
```

I initially read this as "the counter is still broken." It wasn't. Two
separate, unrelated problems were compounding in the same test run:

**Problem 1 — the queue** (expected, not yet fixed)
The `JobQueue` is still the unprotected `ArrayList` from Stage 0. Only
3130 of 10000 jobs survived concurrent `addJob()` calls. This is the same
ArrayList corruption bug from Stage 0 — not a regression, just still present
because the queue isn't fixed until Stage 4/5.

**Problem 2 — my test harness had a race condition, not my counter**
I never called `producers.join()` before starting consumer threads. Consumer
threads started immediately, found the queue still empty or barely filled,
got `null` from `remove()`, and `break`ed out almost instantly — before
producers had added most of their jobs. `Actual: 22` reflects consumers
quitting early, not the counter losing increments.

### Lesson
**Test one variable at a time.** When two systems (queue + counter) are both
potentially broken, a shared test can't tell you which one caused a bad
result. I fixed this by writing an isolated test with NO queue at all —
just N threads calling `counter.increment()` directly:

```java
for (int i = 0; i < NUM_THREADS; i++) {
    Thread t = new Thread(() -> {
        for (int j = 0; j < INCREMENTS_PER_THREAD; j++) {
            jobCounter.increment();
        }
    });
    ...
}
```

This isolated test is what actually validates Stage 1's fix. Result:
`Lost: 0`, every run, deterministically — confirming `synchronized` truly
fixed the atomicity bug, independent of the still-broken queue.

**Takeaway for every future stage:** before concluding a fix doesn't work,
check whether an unrelated, already-known-broken piece of the system is
contaminating the test. Isolate before debugging.

---

## Bugs I Wrote (Repeated Pattern)

### `producerId` hardcoded to `1`
Appeared in both my Stage 0 and Stage 1 code:
```java
final int producerId = 1;  // should be `i`
```
Doesn't affect the counter correctness test, but produces meaningless job
IDs (all producers "claim" ID 0's range). A copy-paste artifact I need to
watch for going forward — the effectively-final-capture pattern
(`final int producerId = i;`) is easy to typo since `1` and `i` look
similar and both compile fine.

### Consumer stopping condition, take 2
Stage 0 taught me not to use `jobCounter.getCount() >= expected` as a
stopping condition, because the counter itself was unreliable at that
point. In Stage 1 I correctly switched to breaking on `null` from the
queue — but introduced the join-ordering bug above instead. Two different
bugs, same underlying lesson: **the order you start/join threads changes
what "queue is empty" is allowed to mean.** Only safe to treat "empty" as
"truly done" when you've already joined every producer.

---

## `JobCounterSynchronized` — Correct on First Try

```java
public synchronized void increment(){
    this.count++;
}
```

`synchronized` on the method locks `this`. Every thread calling
`increment()` on the same instance is mutually excluded. No bugs found
here — this was the one part of my first attempt that worked exactly as
expected.

Minor note for later: `getCount()` reads `count` without synchronization.
Safe in my case because I always call it after every thread has already
been `join()`ed — happens-before is established by `join()` itself, so
there's no concurrent access at read time. Would NOT be safe to call
`getCount()` while increments are still happening concurrently elsewhere.

---

## `JobCounterAtomicInt` — One Design Issue

Original version:
```java
public AtomicInteger getCount(){
    return this.count;
}
```

Returned the whole `AtomicInteger` object instead of unwrapping the `int`
value with `.get()`. Not a correctness bug — `System.out.println` calls
`toString()` on it and happens to print the right number — but it leaks
the internal atomic wrapper to callers and breaks basic arithmetic
(`expected - jobCounter.getCount()` wouldn't compile: can't subtract an
`AtomicInteger` from an `int`).

Fixed:
```java
public int getCount(){
    return count.get();
}
```

Lesson: a getter should return the plain value type the rest of the code
expects, not the internal implementation detail used to make it thread-safe.
Same principle as encapsulation in general — the caller shouldn't need to
know I used `AtomicInteger` internally.

---

## `JobCounterLongAdder` — Correct, Minor Style Cleanup

Logic was correct on first try. Cleanup only:
- Removed an unused `AtomicInteger` import left over from copy-paste
- Changed `getCount()` return type from boxed `Long` to primitive `long`
  — `sum()` already returns primitive `long`; wrapping it in `Long`
  triggers unnecessary autoboxing and reintroduces `Long`'s reference-equality
  trap (`==` on boxed `Long` compares object identity outside the
  -128..127 cache range, not value) if anyone ever compared counts with `==`
- Made the field `private final` — matches the other two classes, and
  the field itself is never reassigned, only mutated internally

---

## Key Concept — Intrinsic Locks (Monitors)

Every Java object has a built-in lock (monitor). `synchronized (obj)`
acquires `obj`'s lock; a `synchronized` method locks `this`. Only one
thread can hold a given object's lock at a time — everyone else trying
to acquire the same lock blocks until it's released, which happens
automatically when the synchronized block/method exits (even on exception).

The critical thing that makes or breaks correctness: **which object is
being locked, and is it the same object across every thread that needs
mutual exclusion?** Locking on `this` in two different instances, or on a
freshly-created object each call, gives you the `synchronized` keyword
with none of its protection — because the threads are never actually
competing for the same lock.

Intrinsic locks are also reentrant — a thread already holding a lock can
re-acquire it (e.g. one synchronized method calling another synchronized
method on the same object) without deadlocking itself. The JVM tracks a
per-thread hold count and only fully releases when it returns to zero.

---

## Key Concept — CAS Retry Loop vs Locking

Understood the mechanical difference between `synchronized` and
`AtomicInteger`:

- `synchronized`: a thread that can't get the lock **blocks** — stops
  running, gets parked by the OS, resumes when the lock frees up. Costs
  a context switch.
- `AtomicInteger`: uses compare-and-swap (CAS), a single hardware
  instruction. A thread that loses the race just **retries immediately**
  — reads the new value, recomputes, tries the CAS again. Never blocks,
  never sleeps, just spins until it succeeds.
  ```
  compareAndSwap(memoryLocation, expectedValue, newValue):
      if memoryLocation currently holds expectedValue:
          set memoryLocation = newValue; return true
      else:
          return false   ← another thread got there first, retry
  ```
- `LongAdder`: same CAS mechanism as `AtomicInteger`, but striped across
  multiple internal cells so concurrent threads are less likely to be
  competing for the exact same memory location. Trades a more expensive
  `sum()` (has to add up every cell) for much less contention on `increment()`.

Expected benchmark ranking under high contention (32 threads, 1M increments
each): `LongAdder` > `AtomicInteger` > `synchronized`. Haven't run the
benchmark yet — that's the next step, using a `CounterBenchmark` harness,
after refactoring the three counter classes to share a common
`JobCounter` interface so the benchmark loop doesn't need `instanceof` checks.

**TODO — fill in after running the benchmark:**
```
Synchronized   | time: ____ms | throughput: ____ ops/sec
AtomicInteger  | time: ____ms | throughput: ____ ops/sec
LongAdder      | time: ____ms | throughput: ____ ops/sec
```

---

## Questions I Can Now Answer Without Looking Anything Up

**Q: Why does `synchronized` guarantee correctness but require blocking?**
A: Only one thread can hold a given object's intrinsic lock at a time.
Every other thread trying to acquire the same lock is paused (blocked)
by the JVM/OS until the lock is released. This guarantees mutual exclusion
but costs a context switch for every thread that has to wait.

**Q: Why can `AtomicInteger` guarantee correctness without ever blocking?**
A: It uses CAS (compare-and-swap), a single atomic CPU instruction that
writes a new value only if the memory still holds the expected old value.
If another thread changed it in between, the CAS fails and the operation
retries with fresh data — no thread is ever paused, they just loop until
they succeed.

**Q: Why did my first Stage 1 test show massive data loss even with a
correct synchronized counter?**
A: The test wasn't isolating the counter — it was still running through
the Stage 0 `ArrayList` queue (which loses jobs) and had a race condition
in the test harness itself (consumers starting before producers finished,
exiting early on a false-empty queue). Both problems were upstream of the
counter and made it impossible to tell if the counter itself was correct.
Isolating the counter with a queue-free test proved it was fine all along.

**Q: When should I use `LongAdder` over `AtomicInteger`?**
A: When many threads are incrementing the same counter under high
contention and you don't need to read the current value frequently.
`LongAdder` spreads writes across internal cells to reduce CAS retries,
at the cost of `sum()` being more expensive than a plain `get()`. For a
counter read once at the end of a test run (like this one) or a metrics
counter incremented far more often than it's read, `LongAdder` is the
right default under high concurrency.

**Q: Two threads call `synchronized` methods on the same object but
different methods — are they mutually exclusive?**
A: Yes. `synchronized` methods on the same instance all lock `this` —
the same lock. It doesn't matter that the method names differ; if both
methods are `synchronized` and called on the same object, only one thread
can be inside either of them at a time.

**Q: If `increment()` is synchronized but a separate `reset()` method
that sets `count = 0` is not, can I still get a bug?**
A: Yes. `synchronized` only protects code that's actually inside a
synchronized block/method on the same lock. An unsynchronized `reset()`
can run concurrently with a synchronized `increment()`, interleaving
with the read-add-write sequence and producing the same kind of lost
update as Stage 0 — just now via a different method instead of two
threads both calling `increment()`. Every access path to shared mutable
state needs to go through the same lock, not just some of them.

---

## Still Pending (before moving to Stage 2)
- [ ] Refactor `JobCounterSynchronized`, `JobCounterAtomicInt`,
      `JobCounterLongAdder` to implement a shared `JobCounter` interface
      (`void increment()`, `long getCount()`)
- [ ] Run the actual throughput benchmark (32 threads, 1,000,000
      increments each) and record real numbers above
- [ ] Fix `producerId` hardcoding in the producer/consumer test file
- [ ] Add `producers.join()` before starting consumers in the
      producer/consumer test file, so it can be reused later (Stage 4/5)
      as a true end-to-end regression test once the queue is also fixed
