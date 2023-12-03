package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.IterablePP;

import java.util.Iterator;
import java.util.Map;

@SuppressWarnings({"rawtypes", "unchecked"})
public class UnpackedInstance implements IterablePP<Map.Entry<String, Object>>{
	
	private final FieldSet<?> fields;
	private final IOInstance  inst;
	
	public <T extends IOInstance<T>> UnpackedInstance(Struct<T> struct, T inst){
		this.fields = struct.getRealFields();
		this.inst = inst;
		if(GlobalConfig.DEBUG_VALIDATION){
			if(inst.getThisStruct() != struct) throw new IllegalArgumentException();
		}
	}
	
	public Object byName(String name){
		return ((IOField)fields.byName(name).orElseThrow()).get(null, inst);
	}
	
	@Override
	public Iterator<Map.Entry<String, Object>> iterator(){
		return fields.map(f -> Map.entry(f.getName(), ((IOField)f).get(null, inst))).iterator();
	}
	
	public FieldSet<?> fields(){
		return fields;
	}
	
}
