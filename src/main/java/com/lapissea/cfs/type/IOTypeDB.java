package com.lapissea.cfs.type;

import java.util.HashMap;
import java.util.Map;

public interface IOTypeDB{
	
	class MemoryOnlyDB implements IOTypeDB{
		
		private final Map<Integer, Class<?>> data=new HashMap<>();
		
		@Override
		public int toID(Class<?> type){
			int max=0;
			for(var e : data.entrySet()){
				if(e.getValue()==type){
					return e.getKey();
				}
				max=Math.max(e.getKey(), max);
			}
			var newID=max+1;
			data.put(newID, type);
			return newID;
		}
		
		@Override
		public Class<?> fromID(int id){
			return data.get(id);
		}
	}
	
	int toID(Class<?> type);
	Class<?> fromID(int id);
	
}
