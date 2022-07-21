package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.Runner;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.time.Instant;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.lapissea.cfs.GlobalConfig.PRINT_COMPILATION;

public abstract class StagedInit{
	
	private static final boolean DO_ASYNC=UtilL.sysPropertyByClass(StagedInit.class, "DO_ASYNC", true, Boolean::valueOf);
	
	private static final ThreadLocal<Deque<StagedInit>> INIT_STACK=ThreadLocal.withInitial(LinkedList::new);
	
	public record StageSnap(StagedInit val, int state){
		
		static StageSnap of(StagedInit val){
			if(val==null) return null;
			return new StageSnap(val, val.getEstimatedState());
		}
		
		@Override
		public String toString(){
			return val+" at "+val.stateToStr(state);
		}
	}
	
	public record StageEvent(StageSnap cause, StageSnap subject, Thread thread, Instant time, String note){}
	
	private static final List<StageEvent> STAGE_EVENTS=Collections.synchronizedList(new LinkedList<>());
	
	static{
		if(PRINT_COMPILATION){
			Runtime.getRuntime().addShutdownHook(new Thread(()->{
				var data=List.copyOf(STAGE_EVENTS);
				LogUtil.println(TextUtil.toTable(data));
			}));
		}
	}
	
	private static void log(StagedInit cause, StagedInit subject, String note){
		var now=Instant.now();
		STAGE_EVENTS.add(new StageEvent(StageSnap.of(cause), StageSnap.of(subject), Thread.currentThread(), now, note));
	}
	
	
	public static void runBaseStageTask(Runnable task){
		if(DO_ASYNC){
			Runner.compileTask(task);
		}else{
			task.run();
		}
	}
	
	public static final int STATE_NOT_STARTED=-1, STATE_START=0, STATE_DONE=Integer.MAX_VALUE;
	
	private final Lock      lock     =new ReentrantLock();
	private final Condition condition=lock.newCondition();
	private       int       state    =STATE_NOT_STARTED;
	private       Throwable e;
	
	
	protected void init(boolean runNow, Runnable init){
		var cause=getCause();
		if(runNow){
			try{
				setInitState(STATE_START);
				logStart(cause);
				init.run();
				setInitState(STATE_DONE);
				logEnd();
			}catch(Throwable e){
				logFail();
				throw e;
			}
			return;
		}
		runBaseStageTask(()->{
			try{
				setInitState(STATE_START);
				logStart(cause);
				init.run();
				setInitState(STATE_DONE);
				logEnd();
			}catch(Throwable e){
				logFail();
				this.e=e;
				try{
					lock.lock();
					condition.signalAll();
				}finally{
					lock.unlock();
				}
			}
		});
	}
	
	protected final void setInitState(int state){
		try{
			lock.lock();
			this.state=state;
			condition.signalAll();
		}finally{
			lock.unlock();
		}
	}
	
	
	private static StagedInit getCause(){
		if(!PRINT_COMPILATION) return null;
		var s=INIT_STACK.get();
		return s.isEmpty()?null:s.getLast();
	}
	
	private void logFail(){
		if(!PRINT_COMPILATION) return;
		
		log(null, this, "FAIL");
		
		var s=INIT_STACK.get();
		if(!s.remove(this)) throw new RuntimeException();
	}
	
	private void logEnd(){
		if(!PRINT_COMPILATION) return;
		
		log(null, this, "Done");
		
		var s=INIT_STACK.get();
		if(!s.remove(this)) throw new RuntimeException();
	}
	private void logStart(StagedInit cause){
		if(!PRINT_COMPILATION) return;
		
		log(cause, this, "Start");
		
		var s=INIT_STACK.get();
		s.addLast(this);
	}
	
	public final void waitForState(int state){
		if(this.state<state){
			waitForState0(state);
			assert this.state>=state;
		}
//		else log(getCause(), this, "No wait");
		
		checkErr();
	}
	
	protected final int getEstimatedState(){
		return state;
	}
	
	public static class WaitException extends RuntimeException{
		public WaitException(String message, Throwable cause){
			super(message, cause);
		}
	}
	
	protected final Throwable getErr(){
		try{
			lock.lock();
			return e;
		}finally{
			lock.unlock();
		}
	}
	
	private void checkErr(){
		try{
			lock.lock();
			
			if(e==null) return;
			throw new WaitException("Exception occurred while initializing: "+this, e);
		}finally{
			lock.unlock();
		}
	}
	
	protected String stateToStr(int state){
		return switch(state){
			case STATE_NOT_STARTED -> "NOT_STARTED";
			case STATE_START -> "START";
			case STATE_DONE -> "DONE";
			default -> "<"+state+">";
		};
	}
	
	private void waitForState0(int state){
		
		StagedInit cause=getCause();
		if(PRINT_COMPILATION){
			log(cause, this, "Wait for   "+stateToStr(state));
		}
		
		while(true){
			try{
				lock.lock();
				checkErr();
				if(this.state>=state){
					if(PRINT_COMPILATION) log(cause, this, "Waited for "+stateToStr(state));
					return;
				}
				try{
					condition.await();
				}catch(InterruptedException ignored){}
			}finally{
				lock.unlock();
			}
		}
	}
}
