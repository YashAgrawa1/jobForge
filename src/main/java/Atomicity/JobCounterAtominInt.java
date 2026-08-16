package Atomicity;

import java.util.concurrent.atomic.AtomicInteger;

public class JobCounterAtominInt {

    AtomicInteger count = new AtomicInteger(0);

    public void increment(){
        this.count.getAndIncrement();
    }

    public AtomicInteger getCount(){
        return this.count;
    }
}
