package Atomicity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class JobCounterLongAdder {

    LongAdder count = new LongAdder();

    public void increment(){
        this.count.increment();
    }

    public Long getCount(){
        return this.count.sum();
    }
}
