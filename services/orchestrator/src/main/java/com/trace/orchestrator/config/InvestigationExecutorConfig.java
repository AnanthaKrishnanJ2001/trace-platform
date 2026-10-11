package com.trace.orchestrator.config;

import com.trace.orchestrator.constant.ServiceConstants;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The bounded worker pool that runs investigations off the request thread,
 * plus scheduling support for the sweeper.
 *
 * <p>The pool is deliberately small and bounded: a burst of exceptions can
 * queue at most {@code executorQueueCapacity} investigations. Beyond that
 * {@code execute} rejects (it does <em>not</em> fall back to running on the
 * caller, which would put the slow Engine call back on the request thread);
 * the rejected exception simply stays {@code DETECTED} and the sweeper picks
 * it up.
 */
@Configuration
@EnableScheduling
public class InvestigationExecutorConfig {

    @Bean(name = ServiceConstants.INVESTIGATION_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor investigationExecutor(InvestigationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("investigation-");
        executor.setCorePoolSize(properties.getExecutorCoreSize());
        executor.setMaxPoolSize(properties.getExecutorMaxSize());
        executor.setQueueCapacity(properties.getExecutorQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
