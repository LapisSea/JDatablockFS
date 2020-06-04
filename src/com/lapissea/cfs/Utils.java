package com.lapissea.cfs;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;

import static com.lapissea.util.UtilL.*;

public class Utils{
	
	public interface ConsumerBaII{
		void accept(byte[] bytes, int off, int len) throws IOException;
	}
	
	public static void zeroFill(OutputStream dest, long size) throws IOException{
		zeroFill(dest::write, size);
	}
	
	public static void zeroFill(ConsumerBaII dest, long size) throws IOException{
		
		var  part=new byte[(int)Math.min(size, 1024)];
		long left=size;
		while(left>0){
			int write=(int)Math.min(left, part.length);
			dest.accept(part, 0, write);
			left-=write;
		}
	}
	
	public static <K, V> boolean isCacheValid(Map<K, V> disk, Map<K, V> cache){
		for(var entry : cache.entrySet()){
			var key=entry.getKey();
			var val=entry.getValue();
			
			var diskVal=disk.get(key);
			if(!val.equals(diskVal)){
				return false;
			}
		}
		return true;
	}
	
	public static void fairDistribute(long[] values, long toDistribute){
		
		long totalUsage=Arrays.stream(values).sum();
		
		var free=toDistribute-totalUsage;
		
		if(free>0){
			int toUse=values.length;
			do{
				var bulkAdd=free/toUse;
				
				for(int i=0;i<toUse;i++){
					values[i]+=bulkAdd;
					free-=bulkAdd;
				}
				toUse--;
			}while(free>0);
		}else{
			Assert(free==0);
		}
		
	}
}
