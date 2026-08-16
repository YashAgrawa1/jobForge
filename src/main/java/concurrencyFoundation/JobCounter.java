package concurrencyFoundation;

public class JobCounter{

    int count = 0;

    public void increment(){
        this.count++;
    }

    public int getCount(){
        return this.count;
    }

}