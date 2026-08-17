# JobForge — Learning Journal

This file is your running record of what broke, why, and what fixed it.
Written in the moment, it will save you hours when you hit the same patterns
in production code years from now.

---

# Stage 0 — Shared Mutable State, No Protection

## What I Built
A naive job processing system: 10 producer threads adding jobs to a shared
ArrayList, 10 consumer threads removing and counting them with a shared int
counter. No synchronization of any kind.

## What I Observed

### Run type 1 — Visible crash
```
Exception in thread "Thread-8" java.lang.ArrayIndexOutOfBoundsException:
Index 76 out of bounds for length 73
    at java.util.ArrayList.add(ArrayList.java:455)
Expected : 10000
Actual   : 159
Lost     : 9841
```
Multiple producer threads crashed mid-execution. They died without finishing
their loops, so most jobs were never added. The tiny actual count (159) is
not from counter corruption — it's from threads that never got to do their work.

### Run type 2 — Silent data loss
```
Expected : 10000
Actual   : 241
Lost     : 9759
Process finished with exit code 0
```
No exception anywhere. Exit code 0. Java reported success. The number is
wildly wrong and nothing in the output tells you that. This is the dangerous
outcome — in production, this looks like a healthy run.

### Run type 3 — Looks correct (also dangerous)
```
Expected : 10000
Actual   : 10000
Lost     : 0
```
Timing happened to work out. This does not mean the code is correct. The
same code that produced 241 also produced 10000 on a different run. Passing
once proves nothing.

---

## Root Cause 1 — `count++` is not atomic

### What I thought it was
A single operation: increment the counter.

### What it actually is
Three separate CPU-level steps:
```
1. READ  — load count from memory into a CPU register  (e.g. reads 5)
2. ADD   — add 1 to the register value                 (5 + 1 = 6)
3. WRITE — store the result back into memory            (writes 6)
```

### Why this breaks under concurrency
Two threads can interleave across these three steps:

```
count = 5

Thread A: READ  → gets 5
Thread B: READ  → gets 5        ← B reads BEFORE A writes back
Thread A: ADD   → 5 + 1 = 6
Thread B: ADD   → 5 + 1 = 6
Thread A: WRITE → count = 6
Thread B: WRITE → count = 6     ← B overwrites A's result
```

Two threads both did work. Both "incremented." count went from 5 to 6,
not 5 to 7. One increment was silently lost. No exception. No warning.

### Why it sometimes gives the right answer
The three steps (read/add/write) execute so fast that most of the time
Thread B starts its READ after Thread A finishes its WRITE. When this
happens by luck, you get the right answer. The bug exists in the code
regardless — whether it triggers depends on CPU scheduling, which you
cannot predict or control.

This is why:
- Running once and getting 10000 does not mean the code is correct
- The bug is harder to reproduce on a single-core machine (less true parallelism)
- Adding print statements can make the bug disappear (I/O introduces delays
  that change thread interleaving timing — the bug is still there, just
  not triggering)

---

## Root Cause 2 — ArrayList is not thread-safe

### What ArrayList does internally when you call add()
The actual JDK 17 source for `add()` looks like this:
```java
// public entry point — snapshots elementData and size into local variables
public boolean add(E e) {
    modCount++;
    add(e, elementData, size);  // passes current elementData reference and size VALUE
    return true;
}

// private implementation — works entirely off local snapshots
private void add(E e, Object[] elementData, int s) {
    if (s == elementData.length)
        elementData = grow();   // resize if full — updates only the LOCAL reference
    elementData[s] = e;         // write item at snapshot index s
    size = s + 1;               // update the real size field
}
```
The critical detail: `elementData` and `size` are passed as local snapshots into the
private method. If another thread replaces the real `elementData` field (via resize)
after this snapshot was taken, this thread is now working with a stale reference to the
old array. This is exactly what causes the crash — explained below.

Again: multiple steps, multiple fields, no protection. Any thread can be paused between
any of these steps.

### Failure mode A — ArrayIndexOutOfBoundsException
This happens because a thread holds a stale snapshot of `elementData` (pointing
to an old, smaller array) while `size` has advanced past that array's length.

Step by step with real numbers:
```
Initial state: size=72, elementData → array of length 73 (one slot left)

Thread A calls add(jobA):
  → public add() snapshots: elementData=<ref to array[73]>, s=72
  → private add() checks: s(72) == length(73)? NO → no resize
  → OS pauses Thread A here, before writing

Thread B calls add(jobB):
  → snapshots: elementData=<ref to array[73]>, s=72
  → checks: s(72) == length(73)? NO → no resize
  → writes array[72] = jobB  ← takes the last available slot
  → size = 73

Thread C calls add(jobC):
  → snapshots: elementData=<ref to array[73]>, s=73
  → checks: s(73) == length(73)? YES → calls grow()
  → grow() creates new array of length 109
  → real elementData field now points to new array[109]
  → writes new_array[73] = jobC
  → size = 74

More threads add items, size advances to 76...

Thread A resumes — still holding its OLD local snapshot:
  → still has: elementData=<ref to OLD array[73]>, s=72
  → tries to write: OLD_array[72] = jobA  (silent overwrite of jobB, no crash yet)
  → size = 73  ← size jumps BACKWARDS, losing jobC and everything after it

Even worse — a thread with a more stale snapshot (s=76) and the old array ref:
  → tries to write: OLD_array[76] = job
  → JVM: ArrayIndexOutOfBoundsException: Index 76 out of bounds for length 73
```

The crash happens because:
1. Thread A snapshotted `elementData` before a resize replaced it
2. `size` advanced past the old array's length in the meantime
3. Thread A tries to write at that advanced index into the smaller old array

This is not a bug in ArrayList. The Javadoc explicitly says ArrayList is
not synchronized. Using it from multiple threads without synchronization
is undefined behavior by contract.

### Failure mode B — Silent slot overwriting
Two threads both snapshot the same value of `size` (say, 72) before either
writes back. Both try to write at index 72:

```
size = 72

Thread A snapshots s=72, Thread B snapshots s=72

Thread A: writes elementData[72] = jobA
Thread B: writes elementData[72] = jobB  ← overwrites jobA
Thread A: size = 73
Thread B: size = 73                       ← size is 73 but only one slot was used

jobA is gone. Not stored anywhere. Not retrievable.
size correctly reflects 73 items, but index 72 holds only jobB.
```

No exception. No indication anything went wrong. The ArrayList's size field
is consistent — it really does have 73 items. But one of the jobs that was
supposed to be stored was silently discarded because two threads wrote to
the same slot.

### Why the exception is actually the BETTER outcome
A crash is visible. It tells you something is wrong. You can find it and
fix it. Silent data loss (Run type 2) looks like a successful execution.
In production, you would not know data was being lost until you audited
your records and found gaps — possibly hours or days later.

---

## The Core Mental Model — What Concurrency Actually Is

Before this stage I thought of code as executing line by line, in order.
That mental model is correct for single-threaded code. It is wrong for
multi-threaded code.

With multiple threads, the CPU is constantly switching between them,
executing a few instructions of Thread A, then a few of Thread B, then
back to Thread A, in an order determined by the OS scheduler — which you
do not control and cannot predict.

Any time two threads access the same memory location and at least one of
them is writing, you have a **race condition**. The "race" is between the
threads to get their read/write in first. The outcome depends on who wins,
which changes every run.

```
Single-threaded mental model (WRONG for concurrent code):
  Line 1 → Line 2 → Line 3 → Line 4 ...

Correct mental model for concurrent code:
  Thread A: Line 1 ──────────────────────── Line 2 → Line 3
  Thread B:          Line 1 → Line 2 ────────────────────── Line 3
  Thread C:                          Line 1 ──────── Line 2
                         (interleaving is unpredictable)
```

---

## The Key Insight About "It Worked When I Tested It"

A test that passes is not evidence of correctness for concurrent code.
It is evidence that the timing happened to work out on this run, on this
machine, under this load.

The same code can be:
- Correct on a single-core machine (no true parallelism, less interleaving)
- Broken on an 8-core machine (real parallel execution, more interleaving)
- Correct under light load (threads rarely overlap)
- Broken under heavy load (threads constantly overlap)
- Correct in development (JVM not warmed up, JIT not optimizing)
- Broken in production (JIT optimizations change memory visibility)

**The only way to be confident concurrent code is correct is to reason
about it — to prove that no harmful interleaving is possible, not to
run it and see if it crashes.**

---

## Stopping Condition Bug I Hit (Personal Note)

My first version used `jobCounter.getCount() >= 10000` as the consumer
stopping condition. The program hung forever.

Why: the counter itself was broken. Under heavy concurrency, count++ was
losing updates — increments were being silently dropped, so the counter
would reach some value below 10000 and stay there permanently. It never
goes backwards (you only ever lose increments, never gain phantom ones),
but it also never reaches 10000 because too many increments were lost.
The consumers spun in while(true) forever, waiting for a number that
would never arrive.

Fix: join all producers first, THEN start consumers. When a consumer sees
an empty queue, it can safely break — it knows no more jobs are coming
because all producers are already done.

Lesson: don't use a broken shared variable to detect when a broken
shared system is finished. The brokenness compounds.

---

## What Is NOT Fixed Yet

| Problem | Fixed in |
|---|---|
| `count++` race condition | Stage 1 (synchronized, AtomicInteger, LongAdder) |
| ArrayList thread safety | Stage 4 (hand-rolled wait/notify queue) |
| Better queue abstraction | Stage 5 (BlockingQueue) |
| Thread pool management | Stage 6 (ThreadPoolExecutor) |

Stage 0 is intentionally left broken. Every subsequent stage fixes one
layer of what broke here — which is why seeing it broken first matters.

---

## Questions I Can Now Answer Without Looking Anything Up

**Q: Why does count++ lose updates under concurrency?**
A: Because it's three operations (read, add, write), not one. Two threads
can both read the same value before either writes back, causing one
write to overwrite the other. The net effect is a lost increment.

**Q: Would the bug still occur with 1 producer and 1 consumer?**
A: The counter race condition requires two threads to overlap on the
same variable. With 1 producer and 1 consumer, only one thread calls
increment() at a time (the consumer), so the counter race is unlikely
to trigger in practice — but the ArrayList is still accessed by both
threads (producer adds, consumer removes), so ArrayList corruption
can still occur. One thread doesn't make the code correct; it just
makes the race condition less likely to trigger.

**Q: Is exit code 0 with wrong output a bug or working as designed?**
A: It's a bug. Java has no way to know your counter has the wrong value —
it just sees the program completed without an unhandled exception. The
"success" signal from the OS only means "the program didn't crash."
It says nothing about whether the program did the right thing.

**Q: Why did adding a println sometimes make the bug go away?**
A: println involves I/O, which is synchronized internally and introduces
memory barriers — points where the JVM flushes cached values to main
memory. This changes the timing of thread interleaving and can
accidentally fix visibility issues. The bug is still in the code; the
println is masking it by changing timing. This is a trap: "it works
with logging on" is not a fix.

---

# Stage 1 — Atomicity

## What I Built
Three counter implementations, each fixing Stage 0's `count++` race a
different way:
- `JobCounterSynchronized` — `synchronized` method around `count++`
- `JobCounterAtomicInt` — `AtomicInteger.incrementAndGet()`
- `JobCounterLongAdder` — `LongAdder.increment()`

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
- `LongAdder`: same CAS mechanism as `AtomicInteger`, but striped across
  multiple internal cells so concurrent threads are less likely to be
  competing for the exact same memory location. Trades a more expensive
  `sum()` (has to add up every cell) for much less contention on `increment()`.

Expected benchmark ranking under high contention (32 threads, 1M increments
each): `LongAdder` > `AtomicInteger` > `synchronized`. Haven't run the
benchmark yet — that's the next step, using the `CounterBenchmark` harness,
after refactoring the three counter classes to share a common
`JobCounter` interface so the benchmark loop doesn't need `instanceof` checks.

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

---

# Stage 2 — Visibility

## What I Built
Two worker classes, identical except for one keyword:
- `WorkerBroken` — `private boolean running = true;`
- `WorkerFixed` — `private volatile boolean running = true;`

Both run a tight busy-loop (`while (running) { iterations++; }`) on a
separate thread. Main thread sleeps 2 seconds, calls `stop()`, then does
a timed `join(5000)` and reports whether the worker actually stopped.

## Real Results From My Machine

### Run 1 — WorkerBroken, no instrumentation
```
BUG REPRODUCED: worker is still running 5 seconds after stop() was called.
It never saw the write.
```
Reproduced consistently, every run, no special JVM flags needed
(no `-XX:-TieredCompilation` required — my machine's JIT hoists the
read aggressively on its own).

### Run 2 — WorkerFixed (volatile)
```
Worker ran for : 2942135217 iterations
Worker stopped 0ms after stop() was called.
```
Stopped in 0ms, every run, consistently. ~2.94 billion iterations in the
2-second window before stop() was called — roughly 1.5 billion
iterations/second, which tells me how hot this loop got and explains why
the JIT had such a strong incentive to optimize the read out of the loop.

### Run 3 — WorkerBroken WITH a temporary println added inside the loop
```java
if (iterations % 50_000_000 == 0) {
    System.out.println("still running...");
}
```
```
still running...
still running...
... (repeated ~18 times)
Worker ran for : 900000000 iterations
Worker stopped 105ms after stop() was called.
```
Stopped in 105ms — despite `running` still being a plain, non-volatile
`boolean`. Nothing about the actual bug was fixed. The println call
internally uses a synchronized PrintStream, and that incidental lock
acquisition forced the JIT/CPU to stop caching the read — a side effect
of doing something totally unrelated to the actual fix.

Also notable: iteration count dropped from ~2.94 billion to 900 million
with the println calls present. Debug logging inside a hot loop isn't
free — it meaningfully changed the loop's throughput, not just its
correctness behavior.

### Run 4 — WorkerBroken again, println removed
```
BUG REPRODUCED: worker is still running 5 seconds after stop() was called.
```
Reproduced consistently again, every run. Confirms Run 3 was masking,
not fixing — the bug was still there the whole time, just hidden by an
unrelated side effect.

---

## The Core Lesson, Proven Not Just Read

Before this stage I understood "the JIT can cache a value" as an abstract
fact. After watching Run 1 → Run 3 → Run 4 on my own machine, I understand
it as something I can predict and reproduce on demand:

- A missing happens-before edge doesn't just "sometimes" cause a bug —
  on a hot enough loop, it caused the bug **100% of the time**, reliably,
  with zero variance across many runs.
- Adding unrelated synchronized I/O (println) can mask that exact bug
  with equal reliability, also with zero variance — not a coincidence,
  a predictable side effect of println's internal locking.
- Removing the instrumentation brings the bug back immediately, with no
  code change to the actual field. The field was never fixed. Only the
  incidental side effect was removed.

This means "it works when I add logging" is not weak evidence of
correctness — it's actively misleading evidence, because the exact
mechanism that makes it "work" (incidental synchronization) is guaranteed
to be absent again the moment the logging is removed for production.

---

## Mechanism — Why `volatile` Fixed It

`running` being plain `boolean` meant there was no happens-before edge
connecting `stop()`'s write (on the main thread) to `run()`'s read (on
the worker thread). The JVM was legally allowed to assume nothing in the
loop body changes `running` (nothing in run() writes to it) and hoist the
read outside the loop entirely — effectively transforming my loop into
something like:
```java
boolean cachedRunning = running;  // read once, before the loop
while (cachedRunning) { iterations++; }  // never re-checks memory again
```
This is legal single-threaded reasoning applied by the JIT, and it's
exactly why the worker thread never noticed the write from another thread.

`volatile boolean running` forbids this optimization specifically for
this field: every read must go back to main memory, every write must be
published to main memory immediately, and the JMM guarantees a
happens-before edge between a volatile write and any subsequent volatile
read of the same field. That's the entire mechanism — no locking, no
blocking, just a guarantee that caching/hoisting can't happen for this
one field.

---

## Mechanism — Why println Masked It (Without Fixing It)

`System.out.println` internally does `synchronized (this) { ... }` on the
PrintStream object. Acquiring and releasing that lock establishes its own
happens-before edge (lock's unlock happens-before the next lock on the
same object) and, more relevantly here, the JMM's ordering rules around
synchronized blocks prevented the JIT from treating my loop body as
"nothing here could possibly need a fresh memory read" — because now
there IS a synchronization point inside the loop, just one that has
nothing to do with `running` itself.

The mutual exclusion println provides was never the reason the bug
disappeared — I only have one worker thread calling println, so there
was no actual contention on that lock. It's the JMM ordering
side effect of lock acquisition/release, unrelated to println's stated
purpose, that accidentally forced a fresh read of `running` on each
iteration.

---

## Questions I Can Now Answer Without Looking Anything Up

**Q: Why does adding a print statement inside a loop sometimes fix a
visibility bug?**
A: It doesn't fix it — it masks it. `println` internally synchronizes,
and synchronization side effects can force the JIT/CPU to re-read memory
instead of trusting a cached value, incidentally providing a happens-before
edge that was never actually established for the variable in question.
Remove the print statement and the bug returns immediately, because
nothing about the actual field changed.

**Q: My code has a `volatile int counter` and two threads both do
`counter++`. Is this safe?**
A: No. `volatile` guarantees visibility (every read sees the latest write)
and ordering (no reordering across the volatile access), but `count++` is
still three separate steps — read, add, write — and `volatile` does
nothing to make those three steps atomic. Two threads can still interleave
between them and lose an update, exactly like Stage 0's `count++` bug.
Visibility and atomicity are different guarantees; volatile only provides
the first one.

**Q: How would I design a test to detect a visibility bug automatically,
without a human watching for a hang?**
A: Use a timed `join(timeoutMs)` instead of an indefinite `join()`, then
check `thread.isAlive()` after the timeout. If the thread is still alive,
the bug reproduced. This is exactly what my Stage2 harness does — it
turns "does the program appear to hang" (something I'd have to watch and
judge) into an automated pass/fail assertion I can run repeatedly and
even wire into a CI pipeline later.

**Q: Why was my bug so reliably reproducible without needing
`-XX:-TieredCompilation` or other JVM flags?**
A: My busy-loop ran extremely hot — nearly 3 billion iterations in the
2-second window — giving the JIT maximum opportunity and maximum
incentive to optimize the loop aggressively, including hoisting the
`running` read outside of it. A cooler/shorter loop might not get
JIT-compiled as aggressively in the same window, which is likely why some
environments need extra flags to reliably reproduce this bug and mine
didn't.

---

## Repo Structure for This Stage
```
Visibility/
├── WorkerBroken.java   — plain boolean, reproduces the hang reliably
├── WorkerFixed.java    — volatile boolean, fixes it completely
└── Stage2.java         — shared harness; swap which worker is
                           instantiated by commenting/uncommenting
```
