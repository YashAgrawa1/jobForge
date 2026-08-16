package concurrencyFoundation;

import java.util.ArrayList;
import java.util.List;

public class JobQueue{

    private final List<Job> jobs = new ArrayList<>();

    public void addJob(Job job){
        jobs.add(job);
    }

    public Job remove(){
        if(jobs.isEmpty()){
            return null;
        }
        return jobs.remove(0);
    }

    public boolean isEmpty(){
        return jobs.isEmpty();
    }

}
