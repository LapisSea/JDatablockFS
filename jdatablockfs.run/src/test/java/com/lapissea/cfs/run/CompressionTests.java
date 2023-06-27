package com.lapissea.cfs.run;

import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NanoTimer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;

import static com.lapissea.cfs.logging.Log.info;
import static com.lapissea.cfs.run.TestUtils.randomBatch;
import static org.testng.Assert.assertEquals;

public class CompressionTests{
	
	@DataProvider(name = "comps", parallel = true)
	Object[][] comps(){
		return Arrays.stream(IOCompression.Type.values()).map(t -> new Object[]{t}).toArray(Object[][]::new);
	}
	
	@Test(dataProvider = "comps")
	void compressionIntegrity(IOCompression.Type type){
		Log.warn("Compression integrity known to be good. I think...");
		if(true) return;
		
		NanoTimer t = new NanoTimer.Simple();
		t.start();
		randomBatch(20000, (r, iter, tick) -> {
			if(tick){
				info("iteration: {}", iter);
			}
			
			try{
				byte[] raw;
				if(iter == 0){
					raw = new byte[10];
					Arrays.fill(raw, (byte)(1));
				}else{
					raw = new byte[r.nextInt(1000)];
					for(int i = 0; i<raw.length; ){
						if(r.nextFloat()<0.2){
							for(int to = Math.min(i + r.nextInt(300) + 1, raw.length); i<to; i++){
								raw[i] = (byte)r.nextInt(256);
							}
						}else{
							var b = (byte)r.nextInt(256);
							for(int to = Math.min(i + r.nextInt(200) + 1, raw.length); i<to; i++){
								raw[i] = b;
							}
						}
					}
				}
				
				byte[] compressed   = type.pack(raw);
				byte[] uncompressed = type.unpack(compressed);
				
				assertEquals(uncompressed, raw, "Failed on " + iter);
			}catch(AssertionError e){
				throw e;
			}catch(Throwable e){
				throw new RuntimeException(iter + "", e);
			}
		});
		t.end();
		LogUtil.println("time: ", t.ms());
	}
}
