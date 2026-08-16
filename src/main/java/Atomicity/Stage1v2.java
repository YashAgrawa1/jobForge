package Atomicity;

import concurrencyFoundation.JobCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Stage1v2 {

    static final int NUM_THREADS = 10;
    static final int INCREMENTS_PER_THREAD = 1000;

    public static void main(String[] args) throws InterruptedException {

        //JobCounterSynchronized jobCounter = new JobCounterSynchronized();
        //JobCounterAtominInt jobCounter = new JobCounterAtominInt();
        JobCounterLongAdder jobCounter = new JobCounterLongAdder();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < NUM_THREADS; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < INCREMENTS_PER_THREAD; j++) {
                    jobCounter.increment();  // no queue involved at all
                }
            });
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) t.join();

        int expected = NUM_THREADS * INCREMENTS_PER_THREAD;
        //int actual = jobCounter.count;
        Long actual   = jobCounter.getCount();

        System.out.println("Expected : " + expected);
        System.out.println("Actual   : " + actual);
        System.out.println("Lost     : " + (expected - actual));
    }
}