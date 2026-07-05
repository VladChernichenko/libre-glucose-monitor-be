package che.glucosemonitorbe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Dedicated multi-threaded scheduler for all {@code @Scheduled} jobs.
     *
     * <p>Without this bean Spring's default {@code @Scheduled} executor is <b>single-threaded</b>,
     * which would serialise every scheduler onto one thread. The nightly
     * {@code DigitalTwinCalibrationScheduler} replays the Hovorka ODE over 30 days of CGM for every
     * user and can run for minutes; on a single thread it would starve the frequent jobs — the 5-min
     * Nightscout/LibreLinkUp CGM syncs, the 5-min anomaly detector, and the 15-min verification poll
     * — stalling CGM ingestion and alerting for all users while it runs.
     *
     * <p>Pool size = the number of distinct scheduled methods (6) so each can run on its own thread
     * and a long job can never block a frequent one. Spring auto-detects a {@link TaskScheduler}
     * bean named {@code taskScheduler} and uses it for all {@code @Scheduled} tasks. Note this does
     * not make a single scheduled method re-enter concurrently with itself — {@code fixedDelay}/cron
     * still wait for the prior run to finish before the next fires; it only decouples the schedulers
     * from each other.
     */
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Dedicated pool for offloading chart-data persistence off the request thread.
     * Bounded queue + CallerRunsPolicy so that if we ever saturate, the request thread absorbs
     * the write (preserving correctness) instead of dropping it.
     */
    @Bean(name = "chartPersistExecutor")
    public Executor chartPersistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("chart-persist-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
