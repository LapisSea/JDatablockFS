package com.lapissea.dfs.run;

import com.lapissea.dfs.benchmark.IOWalkBench;
import com.lapissea.util.LogUtil;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class JMHRun{
	
	public static void run(String[] args){
		
		var name = IOWalkBench.class.getSimpleName() + (args.length == 0? "" : "." + args[0]);
		LogUtil.println("Running", name);
		
		var b = new OptionsBuilder().include(name);
		for(int i = 0; i<args.length; i++){
			if(args[i].equals("-forks")){
				int fork;
				try{
					fork = Integer.parseInt(args[i + 1]);
				}catch(Throwable e){
					new RuntimeException("Failed to parse -forks", e).printStackTrace();
					return;
				}
				LogUtil.println("With", fork, "forks");
				b = b.forks(fork);
			}
		}
		LogUtil.println("==================================================");
		try{
			new Runner(b.build()).run();
		}catch(RunnerException e){
			e.printStackTrace();
		}
	}
	
}
