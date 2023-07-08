package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.utils.ClosableLock;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import static com.lapissea.util.UtilL.async;

public final class StringsIndex{
	private final IOList<String>       data;
	private final Map<String, Integer> reverseIndex = new HashMap<>();
	private final ClosableLock         lock         = ClosableLock.reentrant();
	
	public static String get(IOList<String> strings, int head) throws IOException{
		if(head == 0) return null;
		return strings.get(head - 1);
	}
	
	public StringsIndex(IOList<String> data){
		this.data = data;
		
		try(var ignored = lock.open()){
			reverseIndex.clear();
			for(var late : IntStream.range(0, Math.toIntExact(data.size()))
			                        .mapToObj(i -> async(() -> Map.entry(data.getUnsafe(i), i)))
			                        .toList()){
				try{
					var e = late.join();
					reverseIndex.put(e.getKey(), e.getValue());
				}catch(CompletionException e){
					throw new RuntimeException(e.getCause());
				}
			}
		}
	}
	
	public int make(String val) throws IOException{
		if(val == null) return 0;
		
		try(var ignored = lock.open()){
			var existing = reverseIndex.get(val);
			if(existing != null) return existing;
			
			int newId = Math.toIntExact(data.size() + 1);
			data.add(val);
			reverseIndex.put(val, newId);
			return newId;
		}
	}
}
