package com.lapissea.dfs.run;

import com.google.gson.GsonBuilder;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mostly just used for JMH single shot run profiling.
 */
public final class Sampling{
	
	public static final class Exec{
		private boolean end, started, ended;
		public void end(){
			end = true;
		}
		public void waitStarted(){
			UtilL.sleepUntil(() -> started);
		}
		public void waitEnded(){
			UtilL.sleepUntil(() -> ended);
		}
	}
	
	public static Exec sampleThread(String logName, boolean all, String interest, boolean print){
		var exec = new Exec();
		var t    = Thread.currentThread();
		Thread.ofPlatform().start(() -> {
			Map<List<String>, Long> count = new HashMap<>();
			if(logName != null) readLog(logName, count);
			
			var lines = new ArrayList<String>(32);
			LogUtil.println("started");
			exec.started = true;
			while(!exec.end){
				for(var tt : all? Thread.getAllStackTraces().entrySet() : List.of(Map.entry(t, t.getStackTrace()))){
					if(tt.getKey() == Thread.currentThread()) continue;
					var trace = tt.getValue();
					if(trace.length == 0 || List.of("wait0", "park").contains(trace[0].getMethodName())){
						continue;
					}
					lines.clear();
					var noInterest = interest != null;
					for(var e : trace){
						var s = e.toString().replaceFirst("Lambda/0x[0-9A-Fa-f]+", "Lambda_NUM");
						if(noInterest && s.contains(interest)){
							noInterest = false;
						}
						lines.add(s);
					}
					if(noInterest) continue;
					count.compute(List.copyOf(lines), (k, v) -> {
						return v == null? 1 : v + 1;
					});
				}
				UtilL.sleep(1);
			}
			
			if(print){
				var avg = (long)Iters.values(count).mapToLong(i -> i).average().orElse(0);
				LogUtil.Init.detach();
				LogUtil.println(
					Iters.entries(count).filter(e -> e.getValue()>avg).sortedByL(e -> -e.getValue()).limit(10)
					     .map(e -> e.getValue() + "\n" + Iters.from(e.getKey()).joinAsStr("\n", l -> "\t" + l))
					     .joinAsStr("\n\n")
				);
				LogUtil.println(avg);
			}
			
			if(logName != null) writeLog(logName, count);
			LogUtil.println("ended");
			exec.ended = true;
		});
		return exec;
	}
	
	private static void writeLog(String logName, Map<List<String>, Long> count){
		try(var f = new FileWriter(logName)){
			var m = Iters.entries(count).sorted(Comparator.<Map.Entry<List<String>, Long>>comparingLong(
				e -> -e.getValue()).thenComparing(e -> e.getKey().size())
			).toModList(e -> {
				return Map.of("trace", e.getKey(), "samples", e.getValue());
			});
			f.write(new GsonBuilder().setPrettyPrinting().create().toJson(m));
		}catch(Throwable e){
			LogUtil.println(e);
		}
	}
	private static void readLog(String logName, Map<List<String>, Long> count){
		try(var f = new FileReader(logName)){
			new GsonBuilder().create().<ArrayList<Map<?, ?>>>fromJson(f, ArrayList.class).forEach(m -> {
				count.put(List.copyOf((List<String>)m.get("trace")), ((Number)m.get("samples")).longValue());
			});
		}catch(Throwable e){
			LogUtil.println(e);
			count.clear();
		}
	}
}
