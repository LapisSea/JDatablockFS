package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.run.Sampling;
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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class StartingBench{
	
	
	public static void main(String[] args) throws Exception{
		if(args.length == 0){
			System.out.print("Enter mode: ");
			args = new Scanner(System.in).nextLine().trim().split(" ", 2);
		}
		var opt  = new OptionsBuilder().include(StartingBench.class.getSimpleName());
		var mode = args.length>=1? args[0] : "";
		if(mode.equals("json")){
			opt.resultFormat(ResultFormatType.JSON);
			var date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm_dd-MM-yyyy"));
			opt.result("benchmarks/" + StartingBench.class.getSimpleName() + " " + date + ".json");
		}
		opt.jvmArgsAppend("-Dmode=" + mode);
		new Runner(opt.build()).run();
	}
	
	static Sampling.Exec exec;
	
	@Setup(Level.Trial)
	public void start(){
		if(System.getProperty("mode").equals("sample")){
			exec = Sampling.sampleThread("samples.json", false, null, false);
			exec.waitStarted();
			System.out.println("Sampling...");
		}
	}
	@TearDown(Level.Trial)
	public void end(){
		if(exec != null) exec.waitEnded();
	}
	
	@Benchmark
	@Fork(value = 600, warmups = 5)
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
		if(exec != null) exec.end();
	}
	
}
