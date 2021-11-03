package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public interface IOTypeDB{
	
	class MemoryOnlyDB implements IOTypeDB{
		
		private final Map<Integer, TypeDefinition> idToTyp=new HashMap<>();
		private final Map<TypeDefinition, Integer> typToID=new HashMap<>();
		
		@Override
		public int toID(Class<?> type){
			return toID(TypeDefinition.of(type));
		}
		@Override
		public int toID(TypeDefinition type){
			synchronized(typToID){
				var id=typToID.get(type);
				if(id!=null) return id;
				return newID(type);
			}
		}
		
		private int newID(TypeDefinition type){
			var newID=maxID()+1;
			idToTyp.put(newID, type);
			typToID.put(type, newID);
			return newID;
		}
		
		@Override
		public TypeDefinition fromID(int id){
			synchronized(typToID){
				var type=idToTyp.get(id);
				if(type==null){
					throw new RuntimeException("Unknown type from ID of "+id);
				}
				return type;
			}
		}
		
		public boolean hasType(TypeDefinition type){
			return typToID.containsKey(type);
		}
		public boolean hasID(int id){
			return idToTyp.containsKey(id);
		}
		
		private int maxID(){
			return idToTyp.keySet().stream().mapToInt(i->i).max().orElse(0);
		}
	}
	
	class PersistentDB extends IOInstance<PersistentDB> implements IOTypeDB{
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private IOMap<Integer, TypeDefinition> data;
		
		private static final MemoryOnlyDB BUILT_IN=new MemoryOnlyDB();
		
		static{
			BUILT_IN.toID(Integer.class);
			BUILT_IN.toID(String.class);
		}
		
		@Override
		public int toID(TypeDefinition type) throws IOException{
			if(BUILT_IN.hasType(type)){
				return BUILT_IN.toID(type);
			}
			
			int max=0;
			for(var entry : data.entries()){
				if(entry.getValue().equals(type)){
					return entry.getKey();
				}
				max=Math.max(entry.getKey(), max);
			}
			
			max=Math.max(BUILT_IN.maxID(), max);
			
			var newID=max+1;
			data.put(newID, type);
			return newID;
		}
		
		@Override
		public TypeDefinition fromID(int id) throws IOException{
			if(BUILT_IN.hasID(id)){
				return BUILT_IN.fromID(id);
			}
			
			var type=data.get(id);
			if(type==null){
				throw new RuntimeException("Unknown type from ID of "+id);
			}
			
			return type;
		}
		public void init(ChunkDataProvider provider) throws IOException{
			allocateNulls(provider);
		}
	}
	
	default int toID(Class<?> type) throws IOException{
		return toID(TypeDefinition.of(type));
	}
	
	int toID(TypeDefinition type) throws IOException;
	TypeDefinition fromID(int id) throws IOException;
	
}
