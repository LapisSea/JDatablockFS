package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.collections.AbstractUnmanagedIOMap;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;


/**
 * This interface provides a simple protocol to convert a lengthy type definition in to an ID and back.
 * The implementation is expected to be used mainly as a way to remove excessive repeating when
 * interacting with a value of unknown implicit type.
 */
public interface IOTypeDB{
	
	class MemoryOnlyDB implements IOTypeDB{
		
		private final Map<String, TypeDef> defs=new HashMap<>();
		
		private final Map<Integer, TypeLink> idToTyp=new HashMap<>();
		private final Map<TypeLink, Integer> typToID=new HashMap<>();
		
		private WeakReference<ClassLoader> templateLoader=new WeakReference<>(null);
		
		@Override
		public TypeID toID(TypeLink type, boolean recordNew){
			synchronized(typToID){
				var id=typToID.get(type);
				if(id!=null) return new TypeID(id, true);
				return newID(type, recordNew);
			}
		}
		
		private TypeID newID(TypeLink type, boolean recordNew){
			var newID=maxID()+1;
			if(!recordNew) return new TypeID(newID, false);
			idToTyp.put(newID, type);
			typToID.put(type, newID);
			
			recordType(type);
			return new TypeID(newID, true);
		}
		
		private void recordType(TypeLink type){
			if(!defs.containsKey(type.getTypeName())){
				var def=new TypeDef(type.getTypeClass(null));
				if(!def.isUnmanaged()){
					defs.computeIfAbsent(type.getTypeName(), n->new TypeDef(type.getTypeClass(null)));
				}
				
				for(TypeDef.FieldDef field : def.getFields()){
					recordType(field.getType());
				}
			}
			for(int i=0;i<type.argCount();i++){
				recordType(type.arg(i));
			}
		}
		
		@Override
		public TypeLink fromID(int id){
			synchronized(typToID){
				var type=idToTyp.get(id);
				if(type==null){
					throw new RuntimeException("Unknown type from ID of "+id);
				}
				return type;
			}
		}
		
		public boolean hasType(TypeLink type){
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
		public TypeDef getDefinitionFromClassName(String className){
			if(className==null||className.isEmpty()) return null;
			return defs.get(className);
		}
	}
	
	class PersistentDB extends IOInstance<PersistentDB> implements IOTypeDB{
		
		private static final MemoryOnlyDB BUILT_IN=new MemoryOnlyDB();
		private static final int          FIRST_ID;
		
		static{
			Stream.of(
				      Integer.class,
				      String.class,
				      TypeLink.class,
				      TypeDef.class,
				      IOInstance.class
			      ).flatMap(csrc->Stream.concat(Stream.of(csrc), Arrays.stream(csrc.getDeclaredClasses()).filter(c->UtilL.instanceOf(c, IOInstance.class))))
			      .forEach(c->{
				      try{
					      BUILT_IN.toID(c, true);
				      }catch(IOException e){
					      throw new RuntimeException(e);
				      }
			      });
			
			FIRST_ID=BUILT_IN.maxID();
		}
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private AbstractUnmanagedIOMap<Integer, TypeLink> data;
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private AbstractUnmanagedIOMap<String, TypeDef> defs;
		
		private WeakReference<ClassLoader> templateLoader=new WeakReference<>(null);
		
		@Override
		public TypeID toID(TypeLink type, boolean recordNew) throws IOException{
			var id=BUILT_IN.toID(type, false);
			if(id.stored()) return id;
			
			int max=0;
			for(var entry : data.entries()){
				var key=entry.getKey();
				if(entry.getValue().equals(type)){
					return new TypeID(key, true);
				}
				max=Math.max(key, max);
			}
			
			max=Math.max(FIRST_ID, max);
			
			var newID=max+1;
			if(!recordNew) return new TypeID(newID, false);
			
			data.put(newID, type);
			recordType(type);
			return new TypeID(newID, true);
		}
		
		private void recordType(TypeLink type) throws IOException{
			if(!defs.containsKey(type.getTypeName())){
				var def=new TypeDef(type.getTypeClass(null));
				if(!def.isUnmanaged()&&BUILT_IN.getDefinitionFromClassName(type.getTypeName())==null){
					defs.put(type.getTypeName(), def);
				}
				
				for(TypeDef.FieldDef field : def.getFields()){
					recordType(field.getType());
				}
			}
			for(int i=0;i<type.argCount();i++){
				recordType(type.arg(i));
			}
		}
		
		@Override
		public TypeLink fromID(int id) throws IOException{
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
		public TypeDef getDefinitionFromClassName(String className) throws IOException{
			if(className==null||className.isEmpty()) return null;
			{
				var def=BUILT_IN.getDefinitionFromClassName(className);
				if(def!=null) return def;
			}
			
			return defs.get(className);
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
		@Override
		public String toString(){
			return getClass().getSimpleName()+toShortString();
		}
		
		@Override
		public String toShortString(){
			return "{owner="+data.getDataProvider()+"}";
		}
	}
	
	default TypeID toID(Class<?> type, boolean recordNew) throws IOException{
		return toID(TypeLink.of(type), recordNew);
	}
	
	record TypeID(int val, boolean stored){}
	
	TypeID toID(TypeLink type, boolean recordNew) throws IOException;
	TypeLink fromID(int id) throws IOException;
	
	TypeDef getDefinitionFromClassName(String className) throws IOException;
	
	ClassLoader getTemplateLoader();
}
