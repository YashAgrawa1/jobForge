package concurrencyFoundation;

import java.lang.Thread;

import java.util.ArrayList;
import java.util.List;


public class Stage0{

    static final int NUM_PRODUCERS = 10;
    static final int NUM_CONSUMERS = 10;
    static final int JOBS_PER_PRODUCER = 1000;

    public static void main(String[] args) throws InterruptedException {

        JobCounter jobCounter = new JobCounter();
        JobQueue jobQueue = new JobQueue();

        List<Thread> producers = new ArrayList<>();
        for(int i = 0 ; i < NUM_PRODUCERS; i++){
            final int producerId = 1;
            Thread t = new Thread( () -> {
               for (int j = 0; j < JOBS_PER_PRODUCER; j++){
                   jobQueue.addJob(new Job(producerId*JOBS_PER_PRODUCER + j));
               }
            });
            producers.add(t);
            t.start();
        }

        List<Thread> consumers = new ArrayList<>();
        for (int i = 0; i < NUM_CONSUMERS; i++){
            Thread t = new Thread(() -> {
               while(true){
                   Job job = jobQueue.remove();
                   if(job != null){
                       jobCounter.increment();
                   }else{
                       break;
                       //Thread.yield();
                   }
                   // Commenting this as count may never reach 10000 and we are forever stuck in the loop
                   //if(jobCounter.getCount() >= NUM_PRODUCERS*JOBS_PER_PRODUCER) break;
               }
            });
            consumers.add(t);
            t.start();
        }

        for(Thread t : producers) t.join();
        for(Thread t : consumers) t.join();

        int expected = NUM_PRODUCERS*JOBS_PER_PRODUCER;
        int actual = jobCounter.getCount();
        int lost = expected - actual;

        System.out.println("Expected : " + expected);
        System.out.println("Actual : "+ actual);
        System.out.println("Lost :" + lost );
    }
}