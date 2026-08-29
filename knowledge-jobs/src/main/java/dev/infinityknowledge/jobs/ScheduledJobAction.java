package dev.infinityknowledge.jobs;

/**
 * 由调度模块定时触发的单次后台动作。
 *
 * <p>接口只表达“触发一次”，任务领取、幂等、失败处理和业务状态机仍由对应运行时能力负责，
 * 避免把业务编排复制到调度模块。</p>
 */
@FunctionalInterface
public interface ScheduledJobAction {

    /** 执行一次有界的后台动作。 */
    void execute();
}
