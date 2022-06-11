package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;


/**
 * This interface provides a simple protocol to convert a lengthy type definition in to an ID and back.
 * The implementation is expected to be used mainly as a way to remove excessive repeating when
 * interacting with a value of unknown implicit type.
 */
public sealed interface IOTypeDB{
	
	final class MemoryOnlyDB implements IOTypeDB{
		
		private final Map<String, TypeDef> defs=new HashMap<>();
		
		private final Map<Integer, TypeLink> idToTyp=new HashMap<>();
		private final Map<TypeLink, Integer> typToID=new HashMap<>();
		
		private WeakReference<ClassLoader> templateLoader=new WeakReference<>(null);
		
		@Override
		public TypeID toID(Class<?> type, boolean recordNew){
			return toID(TypeLink.of(type), recordNew);
		}
		
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
				}else{
					defs.put(type.getTypeName(), null);
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
	
	final class PersistentDB extends IOInstance<PersistentDB> implements IOTypeDB{
		
		private static final class TypeName extends IOInstance<TypeName>{
			@IOValue
			private String typeName;
			
			public TypeName(){}
			public TypeName(String typeName){
				this.typeName=typeName;
			}
			
			@Override
			public String toString(){
				return typeName;
			}
			
			@Override
			public String toShortString(){
				var nam  =typeName;
				var index=nam.lastIndexOf('.');
				if(index!=-1) return nam.substring(index+1);
				return nam;
			}
		}
		
		private static final MemoryOnlyDB BUILT_IN=new MemoryOnlyDB();
		private static final int          FIRST_ID;
		
		private static void registerBuiltIn(Class<?> c){
			BUILT_IN.toID(c, true);
			
			for(var dc : c.getDeclaredClasses()){
				if(Modifier.isAbstract(dc.getModifiers())||!IOInstance.isInstance(dc)) continue;
				registerBuiltIn(dc);
			}
		}
		
		static{
			try{
				for(var c : new Class<?>[]{
					int.class,
					long.class,
					float.class,
					double.class,
					Integer.class,
					Long.class,
					Float.class,
					Double.class,
					
					String.class,
					TypeLink.class
				}){
					BUILT_IN.newID(TypeLink.of(c), true);
				}
				for(var c : new Class<?>[]{
					TypeDef.class,
					PersistentDB.class,
					IOInstance.class
				}){
					registerBuiltIn(c);
				}
			}catch(Throwable e){
				e.printStackTrace();
				throw new RuntimeException(e);
			}
			
			FIRST_ID=BUILT_IN.maxID();
		}
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private IOMap<Integer, TypeLink> data;
		
		@IOValue
		@IOValue.OverrideType(HashIOMap.class)
		private IOMap<TypeName, TypeDef> defs;
		
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
			var newDefs=new HashMap<TypeName, TypeDef>();
			recordType(type, newDefs);
			
			defs.putAll(newDefs);
			
			if(DEBUG_VALIDATION){
				checkNewTypeValidity(newDefs);
			}
			return new TypeID(newID, true);
		}
		
		private void checkNewTypeValidity(Map<TypeName, TypeDef> newDefs){
			if(newDefs.isEmpty()) return;
			
			var names=newDefs.entrySet().stream().filter(e->!e.getValue().isUnmanaged()).map(e->e.getKey().typeName).collect(Collectors.toSet());
			var classLoader=new TemplateClassLoader(this, this.getClass().getClassLoader()){
				@Override
				protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
					if(names.contains(name)){
						synchronized(getClassLoadingLock(name)){
							Class<?> c=findLoadedClass(name);
							if(c==null){
								c=findClass(name);
							}
							if(resolve){
								resolveClass(c);
							}
							return c;
						}
					}
					return super.loadClass(name, resolve);
				}
			};
			
			for(var name : names){
				try{
					var cls=Class.forName(name, true, classLoader);
					Struct.ofUnknown(cls);
				}catch(Throwable ex){
					throw new RuntimeException("Invalid stored class "+name+"\n"+TextUtil.toNamedPrettyJson(newDefs.get(new TypeName(name))), ex);
				}
			}
		}
		
		private void recordType(TypeLink type, Map<TypeName, TypeDef> newDefs) throws IOException{
			var isBuiltIn=BUILT_IN.getDefinitionFromClassName(type.getTypeName())!=null;
			if(isBuiltIn) return;
			
			var typeName=new TypeName(type.getTypeName());
			
			var added  =newDefs.containsKey(typeName);
			var defined=defs.containsKey(typeName);
			
			if(added||defined) return;
			
			var typ=type.getTypeClass(null);
			if(typ.isArray()){
				var base=typ;
				while(base.isArray()){
					base=base.componentType();
				}
				recordType(new TypeLink(base), newDefs);
				return;
			}
			
			var def=new TypeDef(typ);
			if(!def.isUnmanaged()){
				newDefs.put(typeName, def);
			}
			
			for(int i=0;i<type.argCount();i++){
				recordType(type.arg(i), newDefs);
			}
			
			if(def.isUnmanaged()) return;
			
			if(DEBUG_VALIDATION){
				ContiguousStructPipe.of(type.getThisStruct()).checkTypeIntegrity(type);
			}
			
			
			for(TypeDef.FieldDef field : def.getFields()){
				recordType(field.getType(), newDefs);
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
			
			return defs.get(new TypeName(className));
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
			if(data==null) return "{uninitialized}";
			return "{"+data.size()+" links, "+defs.size()+" class definitions}";
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
