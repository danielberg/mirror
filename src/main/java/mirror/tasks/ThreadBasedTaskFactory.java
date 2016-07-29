package mirror.tasks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs each task on a dedicated thread, kind of like actors, only more expensive.
 */
public class ThreadBasedTaskFactory implements TaskFactory {

  private final Map<TaskLogic, ThreadBasedTask> tasks = new ConcurrentHashMap<>();

  @Override
  public TaskHandle runTask(TaskLogic logic, Runnable onFailure) {
    ThreadBasedTask task = new ThreadBasedTask(logic, onFailure);
    tasks.put(logic, task);
    return () -> stopTask(logic);
  }

  @Override
  public void stopTask(TaskLogic logic) {
    ThreadBasedTask task = tasks.get(logic);
    if (task == null) {
      throw new IllegalArgumentException("No task found for " + logic);
    }
    tasks.remove(task);
    task.stop();
  }

}