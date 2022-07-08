package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;


/**
 * This interface provides a simple protocol to convert a lengthy type definition in to an ID and back.
 * The implementation is expected to be used mainly as a way to remove excessive repeating when
 * interacting with a value of unknown implicit type.
 */
public sealed interface IOTypeDB{
	final class MemoryOnlyDB implements IOTypeDB{
		
		private final ReadWriteLock rwLock=new ReentrantReadWriteLock();
		
		private final Map<String, TypeDef> defs=new HashMap<>();
		
		private final Map<Integer, TypeLink> idToTyp=new HashMap<>();
		private final Map<TypeLink, Integer> typToID=new HashMap<>();
		private       int                    maxID  =0;
		
		private WeakReference<ClassLoader> templateLoader=new WeakReference<>(null);
		
		@Override
		public TypeID toID(Class<?> type, boolean recordNew){
			return toID(TypeLink.of(type), recordNew);
		}
		
		@Override
		public TypeID toID(TypeLink type, boolean recordNew){
			var lock=rwLock.readLock();
			lock.lock();
			try{
				var id=typToID.get(type);
				if(id!=null) return new TypeID(id, true);
			}finally{
				lock.unlock();
			}
			return newID(type, recordNew);
		}
		
		private TypeID newID(TypeLink type, boolean recordNew){
			var lock=rwLock.writeLock();
			lock.lock();
			try{
				var newID=maxID()+1;
				if(!recordNew) return new TypeID(newID, false);
				idToTyp.put(newID, type);
				typToID.put(type, newID);
				maxID=newID;
				
				recordType(type);
				return new TypeID(newID, true);
			}finally{
				lock.unlock();
			}
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
			var lock=rwLock.readLock();
			lock.lock();
			try{
				var type=idToTyp.get(id);
				if(type==null){
					throw new RuntimeException("Unknown type from ID of "+id);
				}
				return type;
			}finally{
				lock.unlock();
			}
		}
		
		public boolean hasType(TypeLink type){
			var lock=rwLock.readLock();
			lock.lock();
			try{
				return typToID.containsKey(type);
			}finally{
				lock.unlock();
			}
		}
		public boolean hasID(int id){
			var lock=rwLock.readLock();
			lock.lock();
			try{
				return idToTyp.containsKey(id);
			}finally{
				lock.unlock();
			}
		}
		
		private int maxID(){
			return maxID;
		}
		
		@Override
		public ClassLoader getTemplateLoader(){
			var l=templateLoader.get();
			if(l==null){
				templateLoader=new WeakReference<>(l=new TemplateClassLoader(this, getClass().getClassLoader()));
			}
			return l;
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
		
		//Init async, improves first run time, does not load a bunch of classes in static initializer
		private static       int                             FIRST_ID=-1;
		private static final CompletableFuture<MemoryOnlyDB> BUILT_IN=CompletableFuture.supplyAsync(()->{
			var db=new MemoryOnlyDB();
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
					Reference.class,
					}){
					db.newID(TypeLink.of(c), true);
				}
				for(var c : new Class<?>[]{
					TypeDef.class,
					PersistentDB.class,
					IOInstance.class,
					TypeDef.class,
					ObjectID.class,
					ContiguousIOList.class,
					LinkedIOList.class,
					HashIOMap.class,
					}){
					registerBuiltIn(db, c);
				}
			}catch(Throwable e){
				e.printStackTrace();
				throw new RuntimeException(e);
			}
			FIRST_ID=db.maxID();
			return db;
		});
		
		private static void registerBuiltIn(MemoryOnlyDB builtIn, Class<?> c){
			builtIn.toID(c, true);
			
			for(var dc : c.getDeclaredClasses()){
				if(Modifier.isAbstract(dc.getModifiers())||!IOInstance.isInstance(dc)) continue;
				registerBuiltIn(builtIn, dc);
			}
			var cl=c;
			while(cl!=null&&cl!=Object.class){
				
				for(var field : cl.getDeclaredFields()){
					if(field.isAnnotationPresent(IOValue.class)&&IOInstance.isInstance(field.getType())){
						registerBuiltIn(builtIn, field.getType());
					}
				}
				
				cl=cl.getSuperclass();
			}
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
			var builtIn=BUILT_IN.join();
			var id     =builtIn.toID(type, false);
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
			recordType(builtIn, type, newDefs);
			
			defs.putAll(newDefs);
			
			if(DEBUG_VALIDATION){
				newDefs.entrySet().removeIf(e->!e.getValue().isIoInstance());
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
		
		private void recordType(MemoryOnlyDB builtIn, TypeLink type, Map<TypeName, TypeDef> newDefs) throws IOException{
			var isBuiltIn=builtIn.getDefinitionFromClassName(type.getTypeName())!=null;
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
				recordType(builtIn, new TypeLink(base), newDefs);
				return;
			}
			
			var def=new TypeDef(typ);
			if(!def.isUnmanaged()){
				newDefs.put(typeName, def);
			}
			
			for(int i=0;i<type.argCount();i++){
				recordType(builtIn, type.arg(i), newDefs);
			}
			
			if(def.isUnmanaged()) return;
			
			if(DEBUG_VALIDATION){
				ContiguousStructPipe.of(type.getThisStruct()).checkTypeIntegrity(type);
			}
			
			
			for(TypeDef.FieldDef field : def.getFields()){
				recordType(builtIn, field.getType(), newDefs);
			}
		}
		
		@Override
		public TypeLink fromID(int id) throws IOException{
			var builtIn=BUILT_IN.join();
			if(builtIn.hasID(id)){
				return builtIn.fromID(id);
			}
			
			var type=data.get(id);
			if(type==null){
				throw new RuntimeException("Unknown type from ID of "+id);
			}
			
			return type;
		}
		
		@Override
		public TypeDef getDefinitionFromClassName(String className) throws IOException{
			var builtIn=BUILT_IN.join();
			if(className==null||className.isEmpty()) return null;
			{
				var def=builtIn.getDefinitionFromClassName(className);
				if(def!=null) return def;
			}
			
			return defs.get(new TypeName(className));
		}
		
		public void init(DataProvider provider) throws IOException{
			allocateNulls(provider);
		}
		@Override
		public ClassLoader getTemplateLoader(){
			var l=templateLoader.get();
			if(l==null){
				templateLoader=new WeakReference<>(l=new TemplateClassLoader(this, getClass().getClassLoader()));
			}
			return l;
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
