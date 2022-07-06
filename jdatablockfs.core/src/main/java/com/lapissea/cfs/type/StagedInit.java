package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.Runner;
import com.lapissea.util.UtilL;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class StagedInit{
	
	private static final boolean DO_ASYNC=UtilL.sysPropertyByClass(StagedInit.class, "DO_ASYNC", true, Boolean::valueOf);
	
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
		if(runNow){
			setInitState(STATE_START);
			init.run();
			setInitState(STATE_DONE);
			return;
		}
		runBaseStageTask(()->{
			try{
				setInitState(STATE_START);
				init.run();
				setInitState(STATE_DONE);
			}catch(Throwable e){
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
	
	public final void waitForState(int state){
		if(this.state<state){
			waitForState0(state);
			assert this.state>=state;
		}
		
		checkErr();
	}
	
	protected int getEstimatedState(){
		return state;
	}
	
	public static class WaitException extends RuntimeException{
		public WaitException(String message, Throwable cause){
			super(message, cause);
		}
	}
	
	protected Throwable getErr(){
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
			throw new WaitException("Exception occurred while initializing for "+this, e);
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
		while(true){
			try{
				lock.lock();
				checkErr();
				if(this.state>=state) return;
				try{
					condition.await();
				}catch(InterruptedException ignored){}
			}finally{
				lock.unlock();
			}
		}
	}
}
