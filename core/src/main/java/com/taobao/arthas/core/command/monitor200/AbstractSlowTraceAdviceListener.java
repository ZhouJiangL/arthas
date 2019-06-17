package com.taobao.arthas.core.command.monitor200;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.advisor.ReflectAdviceListenerAdapter;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.DateUtils;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.ThreadLocalWatch;
import com.taobao.arthas.core.util.ThreadUtil;

/**
 * @author zjl
 */
public class AbstractSlowTraceAdviceListener extends ReflectAdviceListenerAdapter {

    protected final ThreadLocalWatch threadLocalWatch = new ThreadLocalWatch();
    protected SlowTraceCommand command;
    protected CommandProcess process;
    
    protected final ThreadLocal<TraceEntity> threadBoundEntity = new ThreadLocal<TraceEntity>() {

        @Override
        protected TraceEntity initialValue() {
            return new TraceEntity();
        }
    };
    
    private final ConcurrentLinkedQueue<SlowTraceThreadInfo> slowTraceThreadInfoQueue = new ConcurrentLinkedQueue<SlowTraceThreadInfo>();
    static class SlowTraceThreadInfo {
    	
    	private final Thread listenerThread;
    	
    	private final long beginTimestamp;
    	
    	final Advice advice;
    	
        public SlowTraceThreadInfo(Thread listenerThread, long beginTimestamp, Advice advice) {
        	this.listenerThread = listenerThread;
        	this.beginTimestamp = beginTimestamp;
        	this.advice = advice;
    	}
        
        @Override
        public int hashCode() {
        	return listenerThread.hashCode();
        }
        
        @Override
        public boolean equals(Object obj) {
        	if (obj == null) {
        		return this == null;
        	}
        	SlowTraceThreadInfo other = (SlowTraceThreadInfo)obj;
        	return this.listenerThread == other.listenerThread;
        }
    }
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1, 
            new ThreadFactory() {//线程会不会泄露？
        private AtomicInteger seq = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SlowTraceAdviceListenerExecutorService" + (seq.getAndIncrement()));
            t.setDaemon(true);
            return t;
        }
    });
    
    /**
     * Constructor
     */
    public AbstractSlowTraceAdviceListener(SlowTraceCommand command, CommandProcess process) {
        this.command = command;
        this.process = process;
        int period = 1;
        if (command.getPeriod() > 0) {
        	period = command.getPeriod();
        }
        scheduledExecutorService.scheduleAtFixedRate(new CheckThread(), 0, period, 
        		TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        threadBoundEntity.remove();
        scheduledExecutorService.shutdown();
        LogUtil.getArthasLogger().info("trace listener closed...................................");
    }

    @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args)
            throws Throwable {
        threadBoundEntity.get().view.begin(clazz.getName() + ":" + method.getName() + "()");
        threadBoundEntity.get().deep++;
        // 开始计算本次方法调用耗时
        threadLocalWatch.start();
        
        final Advice advice = Advice.newForBefore(loader, clazz, method, target, args);
        SlowTraceThreadInfo slowTraceThreadInfo = new SlowTraceThreadInfo(Thread.currentThread(), System.nanoTime(), advice);
        if (!slowTraceThreadInfoQueue.contains(slowTraceThreadInfo)) {
        	synchronized (slowTraceThreadInfoQueue) {
        		if (!slowTraceThreadInfoQueue.contains(slowTraceThreadInfo)) {
        			slowTraceThreadInfoQueue.offer(slowTraceThreadInfo);
        		}
			}
        }
    }
    
    @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                               Object returnObject) throws Throwable {
        threadBoundEntity.get().view.end();
        final Advice advice = Advice.newForAfterRetuning(loader, clazz, method, target, args, returnObject);
        finishing(advice);
    }

    @Override
    public void afterThrowing(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                              Throwable throwable) throws Throwable {
        int lineNumber = throwable.getStackTrace()[0].getLineNumber();
        threadBoundEntity.get().view.begin("throw:" + throwable.getClass().getName() + "()" + " #" + lineNumber).end().end();
        final Advice advice = Advice.newForAfterThrowing(loader, clazz, method, target, args, throwable);
        finishing(advice);
    }

    public SlowTraceCommand getCommand() {
        return command;
    }

    private void finishing(Advice advice) {
        if (--threadBoundEntity.get().deep == 0) {
        	SlowTraceThreadInfo slowTraceThreadInfo = new SlowTraceThreadInfo(Thread.currentThread(), 0, null);
        	slowTraceThreadInfoQueue.remove(slowTraceThreadInfo);
        	threadBoundEntity.remove();
        }
    }
    
    protected void shutdownThreadPool() {
    	scheduledExecutorService.shutdown();
	}

	private class CheckThread implements Runnable {
		@Override
        public void run() {
			try {
    			Iterator<SlowTraceThreadInfo> iterator = slowTraceThreadInfoQueue.iterator();
    			while (iterator.hasNext()) {
    				SlowTraceThreadInfo slowTraceThreadInfo = iterator.next();
    				if (slowTraceThreadInfo != null) {
    					long currentTimestamp = System.nanoTime();
            	    	double cost = (currentTimestamp - slowTraceThreadInfo.beginTimestamp) / 1000000.0;
            	    	double cost4EndThreshold = command.getCost4EndThreshold();
            	    	if (cost4EndThreshold > 0 && cost >= cost4EndThreshold) {
            	    		try {
            	    			String stackInfo = ThreadUtil.getThreadStack(slowTraceThreadInfo.listenerThread, 0);
            	        		StringBuilder builder = new StringBuilder();
            	        		builder.append("threshold: " + command.getCost4EndThreshold() + ", totalCost:" + cost + "\n");
            	        		builder.append("ts=" + DateUtils.getCurrentDate() + ";" + stackInfo + "\n");
            	        		builder.append("\n");
            	        		builder.append(threadBoundEntity.get().view.draw() + "\n"); //需要在treeview上加锁
            	        		
            	        		if (isConditionMet(command.getConditionExpress(), slowTraceThreadInfo.advice, cost)) {
            	        			process.times().incrementAndGet();
            	        			process.write(builder.toString());
            	        			if (isLimitExceeded(command.getNumberOfLimit(), process.times().get())) {
            	                        // TODO: concurrency issue to abort process
            	                        abortProcess(process, command.getNumberOfLimit());
            	                    }
            	        		}
            	            } catch (Throwable e) {
            	                LogUtil.getArthasLogger().warn("print overtime stack failed.", e);
            	                process.write("trace failed, condition is: " + command.getConditionExpress() + ", " + e.getMessage()
                                + ", visit " + LogUtil.LOGGER_FILE + " for more details.\n");
                                process.end();
            	            } finally {
            	            	threadBoundEntity.remove();
            	            	iterator.remove();
            	            }
            	        }
    				}
    			}
            } catch (Throwable e) {
            	LogUtil.getArthasLogger().warn("loop queue failed.", e);
            }
        }
    }
}
