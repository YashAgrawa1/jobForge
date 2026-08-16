package concurrencyFoundation;

public class Job {

    private final int id;

    public Job(int id){
        this.id = id;
    }

    public int getId(){
        return this.id;
    }
}