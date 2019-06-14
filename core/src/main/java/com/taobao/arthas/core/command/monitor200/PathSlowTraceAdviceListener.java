package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.shell.command.CommandProcess;

/**
 * @author ralf0131 2017-01-05 13:59.
 */
public class PathSlowTraceAdviceListener extends AbstractSlowTraceAdviceListener {

    public PathSlowTraceAdviceListener(SlowTraceCommand command, CommandProcess process) {
        super(command, process);
    }
}
