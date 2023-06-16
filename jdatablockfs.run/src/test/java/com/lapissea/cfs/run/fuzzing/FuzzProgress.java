package com.lapissea.cfs.run.fuzzing;

import com.lapissea.cfs.logging.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class FuzzProgress{
	
	private final AtomicLong executedCount = new AtomicLong();
	private       int        last          = -1;
	private       Instant    lastLog       = Instant.now();
	private       boolean    hasErr;
	
	private final double totalIterations;
	public FuzzProgress(long totalIterations){ this.totalIterations = totalIterations; }
	
	void err(){
		hasErr = true;
	}
	
	public boolean hasErr(){
		return hasErr;
	}
	
	void inc(){
		if(!Log.INFO) return;
		var count = executedCount.incrementAndGet();
		var val   = (int)(count/totalIterations*1000);
		
		if(val == last) return;
		synchronized(this){
			if(val == last) return;
			var now = Instant.now();
			if(Duration.between(lastLog, now).toMillis()>500){
				lastLog = now;
				last = val;
				Log.info("{}%", String.format("%.1f", val/10D));
			}
		}
	}
}
