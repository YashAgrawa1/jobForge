package Visibility;

public class WorkerFixed implements Runnable{

    private volatile boolean running = true;
    @Override
    public void run() {
        long iterations = 0;
        while(running){
            iterations++;
        }
        System.out.println("Worker ran for : " + iterations + " iterations");

    }

    public void stop(){
        this.running = false;
    }
}
