package com.lapissea.dfs.benchmark;

import com.google.gson.GsonBuilder;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.FileReader;
import java.io.FileWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class StartingBench{
	
	
	private static final class Exec{
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
	private static Exec sampleThread(String logName, boolean all, String interest){
		var exec = new Exec();
		var t    = Thread.currentThread();
		Thread.ofPlatform().start(() -> {
			Map<String, Long> count = new HashMap<>();
			try(var f = new FileReader(logName)){
				new GsonBuilder().create().<HashMap<?, ?>>fromJson(f, HashMap.class).forEach((k, v) -> {
					count.put((String)k, ((Number)v).longValue());
				});
			}catch(Throwable e){
				LogUtil.println(e);
				count.clear();
			}
			
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
					sb.setLength(sb.length() - 1);
					count.compute(sb.toString(), (k, v) -> {
						return v == null? 1 : v + 1;
					});
				}
				UtilL.sleep(1);
			}
			
			if(true){
				var avg = (long)count.values().stream().mapToLong(i -> i).average().orElse(0);
				LogUtil.Init.detach();
				LogUtil.println(count.entrySet().stream().filter(e -> e.getValue()>avg).sorted(Comparator.comparing(e -> -e.getValue())).limit(5).map(e -> e.getValue() + "\n" + e.getKey()).collect(Collectors.joining("\n\n")));
				LogUtil.println(avg);
			}
			
			try(var f = new FileWriter(logName)){
				var m = new LinkedHashMap<>();
				count.entrySet().stream().sorted(Comparator.comparingLong(e -> -e.getValue())).forEach(e -> m.put(e.getKey(), e.getValue()));
				f.write(new GsonBuilder().create().toJson(m));
			}catch(Throwable e){
				LogUtil.println(e);
			}
			LogUtil.println("ended");
			exec.ended = true;
		});
		return exec;
	}
	
	static Exec exec;
	
	@Setup(Level.Trial)
	public void start(){
//		exec = sampleThread("samples.json", false, null);
//		exec.waitStarted();
	}
	@TearDown(Level.Trial)
	public void end(){
//		exec.waitEnded();
	}
	
	@Benchmark
	@Fork(value = 200, warmups = 1)
	@Warmup(iterations = 0)
	@Measurement(iterations = 1)
	@BenchmarkMode(Mode.SingleShotTime)
	public void initAndRoot(){
		try{
			var roots = Cluster.emptyMem().roots();
			roots.request("benchy", Reference.class);
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
//		exec.end();
	}
	
}
