package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.Runner;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.text.DecimalFormat;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class StagedInit{
//	static{
//		LogUtil.Init.attach(0);
//		LogUtil.printTable("ms pass", "", "action", "", "current", " ".repeat(11), "target state", " ".repeat(11), "done", " ".repeat(11), "thread", "", "actor", " ");
//	}
	
	private static final boolean DO_ASYNC=true;//UtilL.sysPropertyByClass(StagedInit.class, "DO_ASYNC", true, Boolean::valueOf);
	
	public static final int STATE_START=0, STATE_DONE=Integer.MAX_VALUE;
	
	private int       state=STATE_START;
	private Throwable e;
	
	private static long last;
	
	protected void init(Runnable init){
		if(last==0) last=System.nanoTime();
		Runnable managedInit=()->{
			try{
				init.run();
			}catch(Throwable e){
				e.printStackTrace();
				this.e=e;
			}finally{
				setInitState(STATE_DONE);
			}
		};
		if(DO_ASYNC){
			Runner.compileTask(managedInit);
		}else{
			managedInit.run();
		}
		
	}
	
	protected final void setInitState(int state){
		synchronized(this){
//			LogUtil.printTable("ms pass", tim(), "actor", this, "thread", Thread.currentThread().getName(), "action", "set", "target state", stateToStrCol(state));
			this.state=state;
			this.notifyAll();
		}
	}
	private String tim(){
		var t=System.nanoTime();
		if(last==0) last=t;
		var dif=t-last;
		last=t;
		var           ddif=dif/1000000D;
		DecimalFormat f   =new DecimalFormat();
//		f.setMinimumIntegerDigits(3);
		f.setMinimumFractionDigits(3);
		f.setMaximumFractionDigits(3);
		return f.format(ddif);
	}
	
	public final void waitForState(int state){
		if(this.state>=state) return;
		waitForState0(state);
		assert this.state>=state;
		var e=this.e;
//		LogUtil.printTable("ms pass", tim(), "actor", this, "thread", Thread.currentThread().getName(), "done", stateToStrCol(state));
		if(e!=null){
			this.e=null;
			throw UtilL.uncheckedThrow(e);
		}
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
		if(DEBUG_VALIDATION){
			var limit=500;
			while(true){
				synchronized(this){
					if(this.state>=state) return;
					try{
//						LogUtil.printTable("ms pass", tim(), "actor", this, "thread", Thread.currentThread().getName(), "action", "wait", "target state", stateToStrCol(state), "current", stateToStrCol(this.state));
						long tim=System.nanoTime();
						this.wait(limit);
						long dif=(System.nanoTime()-tim);
						
						if(dif>(limit-2)*1000_000L){
							if(state>=this.state) throw new ShouldNeverHappenError(this+" didn't wake");
							throw new RuntimeException(this+" "+stateToStrCol(state)+" "+stateToStrCol(this.state)+" took too long");
						}
					}catch(InterruptedException ignored){}
				}
			}
		}else{
			while(true){
				synchronized(this){
					if(this.state>=state) return;
					try{
						this.wait();
					}catch(InterruptedException ignored){}
				}
			}
		}
	}
}
