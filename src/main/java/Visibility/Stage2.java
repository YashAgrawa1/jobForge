package Visibility;

public class Stage2 {

    public static void main(String[] args) throws InterruptedException {

        WorkerBroken worker = new WorkerBroken();
        //WorkerFixed worker = new WorkerFixed();
        Thread workerThread = new Thread(worker);

        workerThread.start();

        Thread.sleep(2000);

        long stopCalledAt = System.nanoTime();
        worker.stop();

        workerThread.join(5000);

        if (workerThread.isAlive()){
            System.out.println("BUG REPRODUCED: worker is still running "
                    + "5 seconds after stop() was called. It never saw the write.");
        }else{
            long stoppedAfterMs = (System.nanoTime() - stopCalledAt) / 1_000_000;
            System.out.println("Worker stopped " + stoppedAfterMs + "ms after stop() was called.");

        }

        System.exit(0);

    }
}
