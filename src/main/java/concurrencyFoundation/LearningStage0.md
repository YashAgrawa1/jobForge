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