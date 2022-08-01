package com.lapissea.cfs.internal;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.logging.AverageDouble;
import com.lapissea.cfs.type.FieldSet;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.text.NumberFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StatIOField{
	
	public static final int WRITE_ACTION=0, READ_ACTION=1, SKIP_READ_ACTION=2;
	
	private record Task(boolean starting, int action, long uid, long nanos){}
	
	private static final int ACTION_SIZE=128;
	private static       int ACTION_POS =0;
	
	private static final int[]     ACTION_BUFF =new int[ACTION_SIZE];
	private static final boolean[] ACTION_PHASE=new boolean[ACTION_SIZE];
	private static final long[]    ACTION_DATA =new long[ACTION_SIZE*2];
	
	private static final List<Runnable>           TASKS  =new LinkedList<>();
	private static final List<Task>               HANGING=new LinkedList<>();
	private static final Map<Long, IOField<?, ?>> INFO   =new HashMap<>();
	
	private static final Map<Long, AverageDouble>[] DELTAS=new Map[]{new HashMap<>(), new HashMap<>(), new HashMap<>()};
	
	static{
		if(StatIOField.class.getClassLoader().getParent()==ClassLoader.getPlatformClassLoader()){
			Runtime.getRuntime().addShutdownHook(new Thread(()->{
				flush();
				printStats();
			}, "Field logging shutdown"));
		}
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
					continue;
				}
				
				flush();
			}
		}, "Action flusher");
		t.setDaemon(true);
		t.start();
	}
	
	private static CharSequence breakdown(int stat, IOField<?, ?> field){
		var avg=fieldAvg(stat, field);
		return breakdown(stat, 0, field, avg, avg);
	}
	
	private static CharSequence breakdown(int stat, int depth, IOField<?, ?> field, double total, double global){
		var form=NumberFormat.getPercentInstance();
		if(!field.typeFlag(IOField.IOINSTANCE_FLAG)){
			return "\t".repeat(depth)+ConsoleColors.BLUE_BRIGHT+field+ConsoleColors.RESET+": "+form.format(fieldAvg(stat, field)/total);
		}
		StringBuilder sb=new StringBuilder();
		sb.append("\t".repeat(depth));
		var avg=fieldAvg(stat, field);
		sb.append(ConsoleColors.GREEN_BRIGHT).append(field).append(": ").append(field.getAccessor().getType().getSimpleName()).append(ConsoleColors.RESET).append(": ")
		  .append(form.format(avg/total)).append(" ")
//		  .append(form.format(avg/global)).append(" global")
		;
		var         s=Struct.ofUnknown(field.getAccessor().getType());
		FieldSet<?> fields;
		fields=s.getFields();
		if(s instanceof Struct.Unmanaged<?> u){
			fields=FieldSet.of((Stream)Stream.concat(fields.stream(), u.getUnmanagedStaticFields().stream()).sorted(Comparator.comparingDouble(f->-fieldAvg(stat, f))));
		}else fields=FieldSet.of(fields.stream().sorted(Comparator.comparingDouble(f->-fieldAvg(stat, f))));
		
		var sum=fields.stream().mapToDouble(f->fieldAvg(stat, f)).sum();
		sb.append(" ").append(ConsoleColors.RED).append(form.format((avg-sum)/avg)).append(" uncounted").append(ConsoleColors.RESET);
		
		for(var sField : fields){
			sb.append('\n');
			sb.append(breakdown(stat, depth+1, sField, avg, global));
		}
		return sb;
	}
	
	private static double fieldAvg(int stat, IOField<?, ?> field){
		return DELTAS[stat].get(field.uid()).getAvg();
	}
	
	private static void printStats(){
		synchronized(DELTAS){
			var names=Stream.of("WRITE", "READ", "SKIP_READ").iterator();
			var inst =NumberFormat.getInstance();
			inst.setMaximumFractionDigits(5);
			for(Map<Long, AverageDouble> delta : DELTAS){
				LogUtil.println(TextUtil.toTable(
					names.next(),
					delta.entrySet()
					     .stream()
					     .filter(e->e.getValue().getCount()>1)
					     .sorted(Comparator.comparingDouble(e->-e.getValue().getTotal()))
					     .map(e->{
						     var m=new LinkedHashMap<>();
						     m.put("name", INFO.get(e.getKey()).toString());
						     m.put("avg", inst.format(e.getValue().getAvg()));
						     m.put("count", e.getValue().getCount());
						     m.put("total", inst.format(e.getValue().getTotal()));
						     return m;
					     }).toList()));
				LogUtil.println(TextUtil.toTable(
					"by type",
					delta.entrySet().stream().filter(e->e.getValue().getCount()>1)
					     .collect(Collectors.groupingBy(e->{
						     var acc=INFO.get(e.getKey()).getAccessor();
						     return acc==null?(Class<?>)Object.class:acc.getType();
					     }))
					     .entrySet()
					     .stream()
					     .map(e->{
						     var m=new LinkedHashMap<>();
						     m.put("type", e.getKey().getSimpleName());
						     m.put("avg", inst.format(e.getValue().stream().mapToDouble(s->s.getValue().getAvg()).average().orElseThrow()));
						     m.put("count", e.getValue().stream().mapToInt(s->s.getValue().getCount()).sum());
						     m.put("total", Math.round(e.getValue().stream().mapToDouble(s->s.getValue().getTotal()).sum()*1000)/1000D);
						     return m;
					     })
					     .sorted(Comparator.comparingDouble(m->-(Double)m.get("total")))
					     .toList()));
				
			}
			
			DELTAS[READ_ACTION].entrySet()
			                   .stream()
			                   .filter(e->e.getValue().getCount()>1)
			                   .sorted(Comparator.comparingDouble(e->-e.getValue().getTotal()))
			                   .limit(3)
			                   .map(e->INFO.get(e.getKey()))
			                   .map(field->breakdown(READ_ACTION, field))
			                   .forEach(obj->LogUtil.println("READ BREAKDOWN\n"+obj));
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
	
	public static synchronized void logRegister(long uid, IOField<?, ?> field){
		synchronized(DELTAS){
			if(DELTAS[0].containsKey(uid)) throw new RuntimeException();
			INFO.put(uid, field);
			for(Map<Long, AverageDouble> delta : DELTAS){
				delta.put(uid, new AverageDouble());
			}
		}
	}
	
	public static synchronized void logStart(int action, long uid){
		if(ACTION_POS==ACTION_SIZE){
			flush();
		}
		var p=ACTION_POS++;
		ACTION_BUFF[p]=action;
		ACTION_PHASE[p]=true;
		ACTION_DATA[p*2]=uid;
		ACTION_DATA[p*2+1]=System.nanoTime();
	}
	public static synchronized void logEnd(int action, long uid){
		var t=System.nanoTime();
		if(ACTION_POS==ACTION_SIZE){
			flush();
		}
		var p=ACTION_POS++;
		ACTION_BUFF[p]=action;
		ACTION_PHASE[p]=false;
		ACTION_DATA[p*2]=uid;
		ACTION_DATA[p*2+1]=t;
	}
	
}
