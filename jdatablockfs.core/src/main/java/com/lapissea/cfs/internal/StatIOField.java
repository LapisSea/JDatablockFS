package com.lapissea.cfs.internal;

import com.lapissea.cfs.logging.AverageDouble;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.*;
import java.util.stream.Stream;

public class StatIOField{
	
	public static final int WRITE_ACTION=0, READ_ACTION=1, SKIP_READ_ACTION=2;
	
	private record Task(boolean starting, int action, long uid, long nanos){}
	
	private static final int ACTION_SIZE=128;
	private static       int ACTION_POS =0;
	
	private static final int[]     ACTION_BUFF =new int[ACTION_SIZE];
	private static final boolean[] ACTION_PHASE=new boolean[ACTION_SIZE];
	private static final long[]    ACTION_DATA =new long[ACTION_SIZE*2];
	
	private static final List<Runnable>    TASKS  =new LinkedList<>();
	private static final List<Task>        HANGING=new LinkedList<>();
	private static final Map<Long, String> INFO   =new HashMap<>();
	
	private static final Map<Long, AverageDouble>[] DELTAS=new Map[]{new HashMap<>(), new HashMap<>(), new HashMap<>()};
	
	static{
		Runtime.getRuntime().addShutdownHook(new Thread(()->{
			flush();
			printStats();
		}, "Field logging shutdown"));
		var t=new Thread(()->{
			while(true){
				if(!TASKS.isEmpty()){
					Runnable task;
					synchronized(TASKS){
						task=TASKS.remove(0);
					}
					task.run();
					continue;
				}
				
				if(ACTION_POS==0){
					UtilL.sleep(1);
					continue;
				}
				
				flush();
			}
		}, "Action flusher");
		t.setDaemon(true);
		t.start();
	}
	
	private static void printStats(){
		synchronized(DELTAS){
			var names=Stream.of("WRITE", "READ", "SKIP_READ").iterator();
			for(Map<Long, AverageDouble> delta : DELTAS){
				var l=delta.entrySet().stream().filter(e->e.getValue().getCount()>1).sorted(Comparator.comparingDouble(e->-e.getValue().getTotal())).map(e->{
					var m=new LinkedHashMap<>();
					m.put("name", INFO.get(e.getKey()));
					m.put("avg", e.getValue().getAvg());
					m.put("count", e.getValue().getCount());
					m.put("total", e.getValue().getTotal());
					return m;
				}).toList();
				LogUtil.println(TextUtil.toTable(names.next(), l));
			}
		}
	}
	
	private static synchronized void flush(){
		int[]     actionBuff =Arrays.copyOf(ACTION_BUFF, ACTION_POS);
		boolean[] actionPhase=Arrays.copyOf(ACTION_PHASE, ACTION_POS);
		long[]    actionData =Arrays.copyOf(ACTION_DATA, ACTION_POS*2);
		ACTION_POS=0;
		Runnable taskR=()->{
			loop:
			for(int i=0;i<actionBuff.length;i++){
				var task=new Task(actionPhase[i], actionBuff[i], actionData[i*2], actionData[i*2+1]);
				if(!task.starting){
					for(var iter=HANGING.listIterator();iter.hasNext();){
						Task hang=iter.next();
						if(!hang.starting) continue;
						if(hang.action!=task.action) continue;
						if(hang.uid!=task.uid) continue;
						iter.remove();
						long delta=task.nanos-hang.nanos;
						reportDelta(task, delta);
						continue loop;
					}
				}else HANGING.add(task);
			}
		};
		synchronized(TASKS){
			TASKS.add(taskR);
		}
	}
	
	private static void reportDelta(Task task, long delta){
		synchronized(DELTAS){
			DELTAS[task.action].get(task.uid).accept(delta/1000_000D);
		}
	}
	
	public static synchronized void logRegister(long uid, String name){
		synchronized(DELTAS){
			if(DELTAS[0].containsKey(uid)) throw new RuntimeException();
			INFO.put(uid, name);
			for(Map<Long, AverageDouble> delta : DELTAS){
				delta.put(uid, new AverageDouble());
			}
		}
	}
	
	public static synchronized void logStart(int action, long uid){
		if(ACTION_POS==ACTION_SIZE) flush();
		ACTION_BUFF[ACTION_POS]=action;
		ACTION_PHASE[ACTION_POS]=true;
		ACTION_DATA[ACTION_POS*2]=uid;
		ACTION_DATA[ACTION_POS*2+1]=System.nanoTime();
		ACTION_POS++;
	}
	public static synchronized void logEnd(int action, long uid){
		var t=System.nanoTime();
		if(ACTION_POS==ACTION_SIZE) flush();
		ACTION_BUFF[ACTION_POS]=action;
		ACTION_PHASE[ACTION_POS]=false;
		ACTION_DATA[ACTION_POS*2]=uid;
		ACTION_DATA[ACTION_POS*2+1]=t;
		ACTION_POS++;
	}
	
}
