package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.utils.IterablePP;

import java.util.Iterator;
import java.util.Map;

public class UnpackedInstance<T extends IOInstance<T>> implements IterablePP<Map.Entry<String, Object>>{
	
	private final Struct<T> struct;
	private final T         inst;
	
	public UnpackedInstance(Struct<T> struct, T inst){
		this.struct = struct;
		this.inst = inst;
	}
	
	public Object byName(String name){
		return struct.getRealFields().byName(name).orElseThrow().get(null, inst);
	}
	
	@Override
	public Iterator<Map.Entry<String, Object>> iterator(){
		return struct.getRealFields().stream().map(f -> Map.<String, Object>entry(f.getName(), f.get(null, inst))).iterator();
	}
}
