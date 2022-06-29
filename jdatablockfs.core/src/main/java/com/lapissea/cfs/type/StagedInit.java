package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.Runner;
import com.lapissea.util.UtilL;

public abstract class StagedInit{
//	static{
//		LogUtil.Init.attach(0);
//		LogUtil.printTable("ms pass", "", "action", "", "current", " ".repeat(11), "target state", " ".repeat(11), "done", " ".repeat(11), "thread", "", "actor", " ");
//	}
	
	private static final boolean DO_ASYNC=UtilL.sysPropertyByClass(StagedInit.class, "DO_ASYNC", true, Boolean::valueOf);
	
	public static void runBaseStageTask(Runnable task){
		if(DO_ASYNC){
			Runner.compileTask(task);
		}else{
			task.run();
		}
	}
	
	public static final int STATE_START=0, STATE_DONE=Integer.MAX_VALUE;
	
	private transient int       state=STATE_START;
	private           Throwable e;

//	private static long last;
	
	protected void init(boolean runNow, Runnable init){
//		if(last==0) last=System.nanoTime();
		if(runNow){
			init.run();
			setInitState(STATE_DONE);
		}
		runBaseStageTask(()->{
			try{
				init.run();
				setInitState(STATE_DONE);
			}catch(Throwable e){
				this.e=e;
				synchronized(this){
					this.notifyAll();
				}
			}
		});
	}
	
	protected final void setInitState(int state){
		synchronized(this){
//			LogUtil.printTable("ms pass", tim(), "actor", this, "thread", Thread.currentThread().getName(), "action", "set", "target state", stateToStrCol(state));
			this.state=state;
			this.notifyAll();
		}
	}
//	private String tim(){
//		var t=System.nanoTime();
//		if(last==0) last=t;
//		var dif=t-last;
//		last=t;
//		var           ddif=dif/1000000D;
//		DecimalFormat f   =new DecimalFormat();
////		f.setMinimumIntegerDigits(3);
//		f.setMinimumFractionDigits(3);
//		f.setMaximumFractionDigits(3);
//		return f.format(ddif);
//	}
	
	public final void waitForState(int state){
		if(this.state<state){
			waitForState0(state);
			assert this.state>=state;
//			LogUtil.printTable("ms pass", tim(), "actor", this, "thread", Thread.currentThread().getName(), "done", stateToStrCol(state));
		}
		
		checkErr();
	}
	
	protected int getState(){
		return state;
	}
	
	public static class WaitException extends RuntimeException{
		public WaitException(String message, Throwable cause){
			super(message, cause);
		}
	}
	
	protected synchronized Throwable getErr(){
		return e;
	}
	
	private synchronized void checkErr(){
		if(e==null) return;
		
		throw new WaitException("Exception occurred while initializing", e);
	}
	
	protected String stateToStrCol(int state){
		return stateToStr(state);
	}
	protected String stateToStr(int state){
		return switch(state){
			case STATE_START -> "START";
			case STATE_DONE -> "DONE";
			default -> "<"+state+">";
		};
	}
	
	private void waitForState0(int state){
//		if(DEBUG_VALIDATION){
//			var limit=1000;
//			while(true){
//				synchronized(this){
//					checkErr();
//					if(this.state>=state) return;
//					try{
////						LogUtil.printTable("ms pass", tim(), "actor", this, "thread", Thread.currentThread().getName(), "action", "wait", "target state", stateToStrCol(state), "current", stateToStrCol(this.state));
//						long tim=System.nanoTime();
//						this.wait(limit);
//						long dif=(System.nanoTime()-tim);
//
//						if(dif>(limit-2)*1000_000L){
//							if(state>=this.state) throw new ShouldNeverHappenError(this+" didn't wake");
//							throw new RuntimeException(this+" "+stateToStrCol(state)+" "+stateToStrCol(this.state)+" took too long");
//						}
//					}catch(InterruptedException ignored){}
//				}
//			}
//		}else{
		while(true){
			synchronized(this){
				checkErr();
				if(this.state>=state) return;
				try{
					this.wait();
				}catch(InterruptedException ignored){}
			}
		}
//		}
	}
}
