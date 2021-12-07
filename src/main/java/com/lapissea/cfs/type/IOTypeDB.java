package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.collections.AbstractUnmanagedIOMap;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;


/**
 * This interface provides a simple protocol to convert a lengthy type definition in to an ID and back.
 * The implementation is expected to be used mainly as a way to remove excessive repeating when
 * interacting with a value of unknown implicit type.
 */
public interface IOTypeDB{
	
	class MemoryOnlyDB implements IOTypeDB{
		
		private final Map<Integer, TypeDefinition> idToTyp=new HashMap<>();
		private final Map<TypeDefinition, Integer> typToID=new HashMap<>();
		
		private WeakReference<ClassLoader> templateLoader=new WeakReference<>(null);
		
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
		@Override
		public ClassLoader getTemplateLoader(){
			synchronized(this){
				var l=templateLoader.get();
				if(l==null){
					templateLoader=new WeakReference<>(l=new TemplateClassLoader(this, getClass().getClassLoader()));
				}
				return l;
			}
		}
		
		@Override
		public TypeDefinition getDefinitionFromClassName(String className){
			if(className==null||className.isEmpty()) return null;
			
			for(var val : idToTyp.values()){
				if(val.getTypeName().equals(className)){
					return val;
				}
			}
			
			return null;
		}
	}
	
	class PersistentDB extends IOInstance<PersistentDB> implements IOTypeDB{
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private IOMap<Integer, TypeDefinition> data;
		
		private static final MemoryOnlyDB BUILT_IN=new MemoryOnlyDB();
		private static final int          FIRST_ID;
		
		static{
			BUILT_IN.toID(Integer.class);
			BUILT_IN.toID(String.class);
			BUILT_IN.toID(TypeDefinition.class);
			
			FIRST_ID=BUILT_IN.maxID();
		}
		
		
		private WeakReference<ClassLoader> templateLoader=new WeakReference<>(null);
		
		@Override
		public int toID(TypeDefinition type) throws IOException{
			if(BUILT_IN.hasType(type)){
				return BUILT_IN.toID(type);
			}
			
			int max=0;
			for(var entry : data.entries()){
				var key=entry.getKey();
				if(entry.getValue().equals(type)){
					return key;
				}
				max=Math.max(key, max);
			}
			
			max=Math.max(FIRST_ID, max);
			
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
		
		@Override
		public TypeDefinition getDefinitionFromClassName(String className) throws IOException{
			if(className==null||className.isEmpty()) return null;
			{
				var def=BUILT_IN.getDefinitionFromClassName(className);
				if(def!=null) return def;
			}
			
			for(var entry : data.entries()){
				var val=entry.getValue();
				if(val.getTypeName().equals(className)){
					return val;
				}
			}
			
			return null;
		}
		
		public void init(DataProvider provider) throws IOException{
			allocateNulls(provider);
		}
		@Override
		public ClassLoader getTemplateLoader(){
			synchronized(this){
				var l=templateLoader.get();
				if(l==null){
					templateLoader=new WeakReference<>(l=new TemplateClassLoader(this, getClass().getClassLoader()));
				}
				return l;
			}
		}
	}
	
	default int toID(Class<?> type) throws IOException{
		return toID(TypeDefinition.of(type));
	}
	
	int toID(TypeDefinition type) throws IOException;
	TypeDefinition fromID(int id) throws IOException;
	
	TypeDefinition getDefinitionFromClassName(String className) throws IOException;
	
	ClassLoader getTemplateLoader();
}
