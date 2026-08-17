package Visibility;

public class WorkerBroken implements Runnable{

    private boolean running = true;
    @Override
    public void run() {
        long iterations = 0;
        while(running){
            iterations++;
//            if (iterations % 50_000_000 == 0) {
//                System.out.println("still running...");   // temporary — proves the trap
//            }
        }
        System.out.println("Worker ran for : " + iterations + " iterations");

    }

    public void stop(){
        this.running = false;
    }
}
