package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
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
			var type=data.get(id);
			if(type==null){
				throw new RuntimeException("Unknown type from ID of "+id);
			}
			return type;
		}
		
		public boolean hasType(Class<?> type){
			return data.containsValue(type);
		}
		public boolean hasID(int id){
			return data.containsKey(id);
		}
	}
	
	class PersistentDB extends IOInstance<PersistentDB> implements IOTypeDB{
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private IOMap<Integer, String> data;
		
		private static final MemoryOnlyDB BUILT_IN=new MemoryOnlyDB();
		
		static{
			BUILT_IN.toID(Integer.class);
			BUILT_IN.toID(String.class);
		}
		
		@Override
		public int toID(Class<?> type) throws IOException{
			if(BUILT_IN.hasType(type)){
				return BUILT_IN.toID(type);
			}
			
			String nam=type.getName();
			int    max=0;
			for(var entry : data.entries()){
				if(entry.getValue().equals(nam)){
					return entry.getKey();
				}
				max=Math.max(entry.getKey(), max);
			}
			
			var newID=max+1;
			data.put(newID, nam);
			return newID;
		}
		
		@Override
		public Class<?> fromID(int id) throws IOException{
			if(BUILT_IN.hasID(id)){
				return BUILT_IN.fromID(id);
			}
			
			var type=data.get(id);
			if(type==null){
				throw new RuntimeException("Unknown type from ID of "+id);
			}
			try{
				return Class.forName(type);
			}catch(ClassNotFoundException e){
				//TODO: Need to generate classes when their backing code is missing
				throw NotImplementedException.infer();
			}
		}
		public void init(ChunkDataProvider provider) throws IOException{
			allocateNulls(provider);
		}
	}
	
	int toID(Class<?> type) throws IOException;
	Class<?> fromID(int id) throws IOException;
	
}
