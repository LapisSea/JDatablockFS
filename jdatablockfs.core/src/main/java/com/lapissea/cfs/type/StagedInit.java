package com.lapissea.cfs.type;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class StagedInit{
	
	private static final boolean DO_ASYNC=GlobalConfig.configFlag("asyncLoad", true);
	
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
	
	public StagedInit(){
		if(DEBUG_VALIDATION){
			validateStates();
		}
	}
	
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
			
			if(DEBUG_VALIDATION){
				if(state<=this.state){
					throw new IllegalArgumentException("State must advance");
				}
				if(listStates().mapToInt(StateInfo::id).noneMatch(id->id==state)){
					throw new IllegalArgumentException("Unknown state: "+state);
				}
			}
			
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
			throw new WaitException("Exception occurred while initializing: "+this, e);
		}finally{
			lock.unlock();
		}
	}
	
	public record StateInfo(int id, String name){
		private static final List<StateInfo> BASE_STATES=List.of(
			new StateInfo(STATE_NOT_STARTED, "NOT_STARTED"),
			new StateInfo(STATE_START, "START"),
			new StateInfo(STATE_DONE, "DONE")
		);
	}
	
	protected String stateToString(int state){
		return listStates().filter(i->i.id==state).map(StateInfo::name).findAny().orElseGet(()->"UNKNOWN_STATE_"+state);
	}
	
	/***
	 * This method lists information about all states that this object can be in. This method is only
	 * called when debugging or calling toString so performance is not of great concern.
	 */
	protected Stream<StateInfo> listStates(){
		return StateInfo.BASE_STATES.stream();
	}
	
	private void validateStates(){
		Set<Integer>   ids     =new HashSet<>();
		Set<String>    names   =new HashSet<>();
		List<String>   problems=new ArrayList<>();
		Set<StateInfo> base    =new HashSet<>(StateInfo.BASE_STATES);
		
		listStates().forEach(state->{
			if(state.id<STATE_NOT_STARTED) problems.add("\t"+state+": id is too small!");
			if(!ids.add(state.id)) problems.add("\t"+state+": has a duplicate id");
			if(!names.add(state.name)) problems.add("\t"+state+": has a duplicate name");
			base.remove(state);
		});
		for(StateInfo stateInfo : base){
			problems.add("\t"+stateInfo+" is a base state but is not listed!");
		}
		
		if(!problems.isEmpty()){
			StringJoiner str=new StringJoiner("\n");
			str.add("Issues with state IDs for "+this.getClass().getSimpleName()+":");
			problems.forEach(str::add);
			str.add(TextUtil.toTable(listStates().sorted(Comparator.comparingInt(StateInfo::id))));
			throw new ShouldNeverHappenError(str.toString());
		}
		
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
