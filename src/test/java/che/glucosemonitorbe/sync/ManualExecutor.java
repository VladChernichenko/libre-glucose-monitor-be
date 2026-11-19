package che.glucosemonitorbe.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Test executor: queues tasks; the test decides when (and which) to run. */
class ManualExecutor implements Executor {

    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public synchronized void execute(Runnable command) {
        tasks.add(command);
    }

    synchronized int size() {
        return tasks.size();
    }

    /** Runs and removes the task at index. */
    void run(int index) {
        Runnable r;
        synchronized (this) {
            r = tasks.remove(index);
        }
        r.run();
    }

    void runAll() {
        while (true) {
            Runnable r;
            synchronized (this) {
                if (tasks.isEmpty()) {
                    return;
                }
                r = tasks.remove(0);
            }
            r.run();
        }
    }
}
