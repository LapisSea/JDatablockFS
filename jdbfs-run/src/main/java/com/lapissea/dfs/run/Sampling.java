package com.lapissea.dfs.run;

import com.google.gson.GsonBuilder;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.FileReader;
import java.io.FileWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Sampling{
	
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
	public static Exec sampleThread(String logName, boolean all, String interest){
		var exec = new Exec();
		var t    = Thread.currentThread();
		Thread.ofPlatform().start(() -> {
			Map<String, Long> count = new HashMap<>();
			if(logName != null) readLog(logName, count);
			
			var sb = new StringBuilder();
			LogUtil.println("started");
			exec.started = true;
			while(!exec.end){
				for(var tt : all? Thread.getAllStackTraces().entrySet() : List.of(Map.entry(t, t.getStackTrace()))){
					if(tt.getKey() == Thread.currentThread()) continue;
					var trace = tt.getValue();
					sb.setLength(0);
					var noInterest = interest != null;
					for(var e : trace){
						var s = e.toString();
						if(noInterest && s.contains(interest)){
							noInterest = false;
						}
						sb.append('\t').append(s).append('\n');
					}
					if(noInterest) continue;
					if(sb.length()>0 && sb.charAt(sb.length() - 1) == '\n'){
						sb.setLength(sb.length() - 1);
					}
					count.compute(sb.toString(), (k, v) -> {
						return v == null? 1 : v + 1;
					});
				}
				UtilL.sleep(1);
			}
			
			if(true){
				var avg = (long)count.values().stream().mapToLong(i -> i).average().orElse(0);
				LogUtil.Init.detach();
				LogUtil.println(count.entrySet().stream().filter(e -> e.getValue()>avg).sorted(Comparator.comparing(e -> -e.getValue())).limit(10).map(e -> e.getValue() + "\n" + e.getKey()).collect(Collectors.joining("\n\n")));
				LogUtil.println(avg);
			}
			
			if(logName != null) writeLog(logName, count);
			LogUtil.println("ended");
			exec.ended = true;
		});
		return exec;
	}
	
	private static void writeLog(String logName, Map<String, Long> count){
		try(var f = new FileWriter(logName)){
			var m = new LinkedHashMap<>();
			count.entrySet().stream().sorted(Comparator.comparingLong(e -> -e.getValue())).forEach(e -> m.put(e.getKey(), e.getValue()));
			f.write(new GsonBuilder().create().toJson(m));
		}catch(Throwable e){
			LogUtil.println(e);
		}
	}
	private static void readLog(String logName, Map<String, Long> count){
		try(var f = new FileReader(logName)){
			new GsonBuilder().create().<HashMap<?, ?>>fromJson(f, HashMap.class).forEach((k, v) -> {
				count.put((String)k, ((Number)v).longValue());
			});
		}catch(Throwable e){
			LogUtil.println(e);
			count.clear();
		}
	}
}
