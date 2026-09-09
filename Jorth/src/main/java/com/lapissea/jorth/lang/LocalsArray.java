package com.lapissea.jorth.lang;

import com.lapissea.jorth.exceptions.MissingLocalField;
import com.lapissea.jorth.lang.type.GenericType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class LocalsArray implements Iterable<LocalsArray.Local>, Cloneable{
	
	public record Local(String name, GenericType type, int index, boolean canRemove){
		public Local{
			Objects.requireNonNull(name);
			Objects.requireNonNull(type);
		}
		public Local withoutRemoval(){
			return canRemove? new Local(name, type, index, false) : this;
		}
	}
	
	private final Map<String, Local>      localValues = new HashMap<>();
	private final TreeMap<Integer, Local> byIndex     = new TreeMap<>();
	
	public Local getForce(String name) throws MissingLocalField{
		var field = get(name);
		if(field == null){
			throw new MissingLocalField(name + " does not exist");
		}
		return field;
	}
	public Local get(String name){
		return localValues.get(name);
	}
	public boolean has(String name){
		return localValues.containsKey(name);
	}
	public void add(Local local){
		localValues.put(local.name, local);
		byIndex.put(local.index, local);
	}
	public void remove(Local local){
		Objects.requireNonNull(localValues.remove(local.name));
		Objects.requireNonNull(byIndex.remove(local.index));
	}
	
	public int findSlot(int size){
		int cursor = 0;
		for(var entry : byIndex.entrySet()){
			int start = entry.getKey();
			if(start - cursor>=size){
				return cursor;
			}
			cursor = start + entry.getValue().type().getBaseType().slots;
		}
		return cursor;
	}
	@Override
	public Iterator<Local> iterator(){
		return byIndex.values().iterator();
	}
	
	@Override
	public LocalsArray clone(){
		var clone = new LocalsArray();
		clone.localValues.putAll(localValues);
		clone.byIndex.putAll(byIndex);
		return clone;
	}
}
