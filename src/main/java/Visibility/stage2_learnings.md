# Stage 2 — Visibility — LEARNINGS.md

## What I Built
Two worker classes, identical except for one keyword:
- `WorkerBroken` — `private boolean running = true;`
- `WorkerFixed` — `private volatile boolean running = true;`

Both run a tight busy-loop (`while (running) { iterations++; }`) on a
separate thread. Main thread sleeps 2 seconds, calls `stop()`, then does
a timed `join(5000)` and reports whether the worker actually stopped.

```
Visibility/
├── WorkerBroken.java   — plain boolean, reproduces the hang reliably
├── WorkerFixed.java    — volatile boolean, fixes it completely
└── Stage2.java         — shared harness; swap which worker is
                           instantiated by commenting/uncommenting
```

---

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
was no actual contention on that lock. It's the JMM ordering side
effect of lock acquisition/release, unrelated to println's stated
purpose, that accidentally forced a fresh read of `running` on each
iteration.

---

## Two Guarantees, Not One — Visibility vs Ordering vs Atomicity

`volatile` gives two distinct guarantees, and withholds a third that
people often assume it provides:

**1. Visibility** — a write to a volatile field is guaranteed visible to
any thread that subsequently reads it. This is what fixed my worker loop.

**2. Ordering** — the JVM/CPU cannot reorder other reads/writes across a
volatile read/write. This means a plain field written *before* a volatile
write is guaranteed visible to a thread that reads the volatile field
*after* that write — a "publish" pattern where one volatile flag protects
a whole batch of other fields written before it.

**3. Atomicity — NOT provided.** `volatile int counter; counter++;` is
still unsafe. `count++` is read-add-write, three steps, and volatile only
guarantees each individual read or individual write is atomic and visible
— it does nothing to make a read-modify-write sequence indivisible. Two
threads can still interleave between the three steps and lose an update,
exactly like Stage 0's `count++` bug. This is the trap Stage 1 and
Stage 2 both point at from different angles: visibility and atomicity are
orthogonal problems, and volatile only ever solves the first one.

The decision rule I'm carrying forward: does this variable only ever get
a single read or a single write at a time — never a read-modify-write?
If yes, `volatile` alone is correct and is the cheapest option. If there's
any read-modify-write involved, `volatile` is not sufficient — need
`synchronized`, `AtomicInteger`/`LongAdder`, or `ReentrantLock` instead.

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

**Q: What is the Java Memory Model actually promising, in one sentence?**
A: A write by one thread is guaranteed visible to a read by another
thread only if there's a defined happens-before relationship between
them — without one, there is no guarantee at all, not even a "probably,"
regardless of whether the code happens to work in testing.

**Q: Name three happens-before relationships I already rely on without
writing `volatile`.**
A: (1) Everything set up before `Thread.start()` is visible to the
started thread. (2) Everything a thread did is visible to whoever calls
`Thread.join()` on it after it finishes — this is why reading
`jobCounter.getCount()` after joining all threads in Stage 0/1 was safe
even with a plain read. (3) An `unlock` on a given object happens-before
the next `lock` on that same object — this is why `synchronized` gives
visibility as well as mutual exclusion, which is what made
`JobCounterSynchronized` correct in Stage 1 without needing `volatile`
on top of it.

---

## Still Pending (before moving to Stage 3)
- [ ] Try to reproduce the visibility bug on a cooler/shorter loop
      (fewer iterations) and see if it still reproduces as reliably —
      would help build intuition for why "hot loop" mattered here
- [ ] Read up on `jstack`/`jcmd` before starting Stage 3, since Stage 3's
      deadlock detection depends on reading a thread dump for the first time
