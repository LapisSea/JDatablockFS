package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CacheOutOfSync extends IOException{
	
	public CacheOutOfSync(){
	
	}
	
	private static <T> String makeMsg(T cached, T actual){
		assert !Objects.equals(cached, actual);
		if(cached == null){
			return "cached missing, actual: " + actual;
		}
		if(actual == null){
			return "actual missing, cached: " + cached;
		}
		if(cached.getClass() == actual.getClass() && cached instanceof IOInstance<?> i){
			return instScan((IOInstance)cached, (IOInstance)actual);
		}
		String cachedStr;
		try{
			cachedStr = Objects.toString(cached);
		}catch(Throwable e){
			cachedStr = IOFieldTools.corruptedGet(e);
		}
		return cachedStr + "\n" + TextUtil.toTable("cached/actual", cached, actual);
	}
	private static <T extends IOInstance<T>> String instScan(T cached, T actual){
		var    struct = cached.getThisStruct();
		var    sb     = new StringBuilder(32);
		String cachedStr;
		try{
			cachedStr = Objects.toString(cached);
		}catch(Throwable e){
			cachedStr = IOFieldTools.corruptedGet(e);
		}
		sb.append(cachedStr).append('\n');
		
		List<Map<String, String>> bad = new ArrayList<>();
		for(var field : struct.getRealFields()){
			if(field.instancesEqual(null, cached, null, actual)){
				if(sb.length() == 1) sb.append("Ok fields:\n");
				sb.append('\t').append(field.getName()).append(": ").append(field.get(null, cached)).append('\n');
			}else{
				if(bad.isEmpty()) bad.add(Map.of("Name", "Name", "Cached", "Cached", "Actual", "Actual"));
				bad.add(Map.of(
					"Name", field.getName(),
					"Cached", Objects.toString(field.get(null, cached)),
					"Actual", Objects.toString(field.get(null, actual))
				));
			}
		}
		
		if(bad.isEmpty()) sb.append("No mismatching fields?? Something may be wrong with the quality methods");
		else{
			sb.append("Mismatching fields:\n");
			var names = List.of("Name", "Cached", "Actual");
			var lens  = Iters.from(names).mapToInt(n -> Iters.from(bad).mapToInt(m -> m.get(n).length() + 1).max(0)).toArray();
			for(var m : bad){
				sb.append('\t');
				for(int i = 0; i<names.size(); i++){
					String name = names.get(i);
					var    v    = m.get(name);
					sb.append(v).append(" ".repeat(lens[i] - v.length()));
				}
				sb.append('\n');
			}
		}
		return sb.toString();
	}
	
	public <T> CacheOutOfSync(T cached, T actual){
		this(makeMsg(cached, actual));
	}
	
	public CacheOutOfSync(String message){
		super(message);
	}
	
	public CacheOutOfSync(String message, Throwable cause){
		super(message, cause);
	}
	
	public CacheOutOfSync(Throwable cause){
		super(cause);
	}
}
