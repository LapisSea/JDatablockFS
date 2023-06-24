package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.type.compilation.TemplateClassLoader;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.ReadWriteClosableLock;
import com.lapissea.util.LateInit;
import com.lapissea.util.Rand;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lapissea.cfs.config.GlobalConfig.TYPE_VALIDATION;


/**
 * This interface provides a simple protocol to convert a lengthy type definition in to an ID and back.
 * The implementation is expected to be used mainly as a way to remove excessive repeating when
 * interacting with a value of unknown implicit type.
 */
public sealed interface IOTypeDB{
	
	sealed interface MemoryOnlyDB extends IOTypeDB{
		@Override
		TypeID toID(Class<?> type, boolean recordNew);
		@Override
		TypeID toID(TypeLink type, boolean recordNew);
		@Override
		int toID(Class<?> type);
		@Override
		int toID(TypeLink type);
		
		@Override
		TypeLink fromID(int id);
		boolean hasType(TypeLink type);
		boolean hasID(int id);
		
		@Override
		TypeDef getDefinitionFromClassName(String className);
		
		sealed class Basic implements MemoryOnlyDB{
			
			private final Map<String, TypeDef> defs = new HashMap<>();
			
			private final Map<Integer, TypeLink> idToTyp = new HashMap<>();
			private final Map<TypeLink, Integer> typToID = new HashMap<>();
			private       int                    maxID   = 0;
			
			private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
			
			private static class MemUniverse<T>{
				private final Map<Integer, Class<T>> id2cl = new HashMap<>();
				private final Map<Class<T>, Integer> cl2id = new HashMap<>();
				private       int                    idCounter;
				
				public MemUniverse(int start){
					this.idCounter = start;
				}
				
				private int newId(Class<T> type){
					var id = idCounter++;
					cl2id.put(type, id);
					id2cl.put(id, type);
					return id;
				}
			}
			
			private final Map<Class<?>, MemUniverse<?>> sealedMultiverse = new HashMap<>();
			
			@Override
			public TypeID toID(Class<?> type, boolean recordNew){
				return toID(TypeLink.of(type), recordNew);
			}
			
			@Override
			public long typeLinkCount(){ return typToID.size(); }
			@Override
			public long definitionCount(){ return defs.size(); }
			
			@Override
			public TypeID toID(TypeLink type, boolean recordNew){
				var id = typToID.get(type);
				if(id != null) return new TypeID(id, true);
				return newID(type, recordNew);
			}
			
			@Override
			public int toID(Class<?> type){
				return toID(TypeLink.of(type));
			}
			@Override
			public int toID(TypeLink type){
				var id = typToID.get(type);
				if(id != null) return id;
				return newID(type, true).requireStored();
			}
			
			protected TypeID newID(TypeLink type, boolean recordNew){
				var newID = maxID() + 1;
				if(!recordNew) return new TypeID(newID, false);
				idToTyp.put(newID, type);
				typToID.put(type, newID);
				maxID = newID;
				
				recordType(type);
				return new TypeID(newID, true);
			}
			
			private void recordType(TypeLink type){
				if(!defs.containsKey(type.getTypeName())){
					var def = new TypeDef(type.getTypeClass(null));
					if(!def.isUnmanaged()){
						defs.computeIfAbsent(type.getTypeName(), n -> new TypeDef(type.getTypeClass(null)));
					}else{
						defs.put(type.getTypeName(), null);
					}
					
					for(TypeDef.FieldDef field : def.getFields()){
						recordType(field.getType());
					}
				}
				for(int i = 0; i<type.argCount(); i++){
					recordType(type.arg(i));
				}
			}
			
			@Override
			public TypeLink fromID(int id){
				var type = idToTyp.get(id);
				if(type == null){
					throw new RuntimeException("Unknown type from ID of " + id);
				}
				return type;
			}
			
			private <T> MemUniverse<T> getUniverse(Class<T> rootType){
				//noinspection unchecked
				return (MemUniverse<T>)sealedMultiverse.computeIfAbsent(rootType, m -> new MemUniverse<>(1));
			}
			
			@Override
			public <T> Class<T> fromID(Class<T> rootType, int id){
				if(!rootType.isSealed()) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				return universe.id2cl.get(id);
			}
			
			@Override
			public <T> int toID(Class<T> rootType, Class<T> type, boolean record){
				if(!rootType.isSealed()) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				var id       = universe.cl2id.get(type);
				if(id == null){
					id = universe.newId(type);
				}
				return id;
			}
			
			@Override
			public boolean hasType(TypeLink type){
				return typToID.containsKey(type);
			}
			@Override
			public boolean hasID(int id){
				return idToTyp.containsKey(id);
			}
			
			private int maxID(){
				return maxID;
			}
			
			@Override
			public ClassLoader getTemplateLoader(){
				var l = templateLoader.get();
				if(l == null){
					templateLoader = new WeakReference<>(l = new TemplateClassLoader(this, getClass().getClassLoader()));
				}
				return l;
			}
			
			@Override
			public TypeDef getDefinitionFromClassName(String className){
				if(className == null || className.isEmpty()) return null;
				return defs.get(className);
			}
			
			public Fixed bake(){
				return new Fixed(defs, idToTyp);
			}
		}
		
		final class Synchronized extends Basic{
			
			private final ReadWriteClosableLock rwLock = ReadWriteClosableLock.reentrant();
			
			@Override
			public int toID(TypeLink type){
				try(var ignored = rwLock.read()){
					return super.toID(type);
				}
			}
			
			@Override
			protected TypeID newID(TypeLink type, boolean recordNew){
				try(var ignored = rwLock.write()){
					return super.newID(type, recordNew);
				}
			}
			
			@Override
			public TypeLink fromID(int id){
				try(var ignored = rwLock.read()){
					return super.fromID(id);
				}
			}
			
			@Override
			public boolean hasType(TypeLink type){
				try(var ignored = rwLock.read()){
					return super.hasType(type);
				}
			}
			@Override
			public boolean hasID(int id){
				try(var ignored = rwLock.read()){
					return super.hasID(id);
				}
			}
			@Override
			public <T> Class<T> fromID(Class<T> rootType, int id){
				try(var ignored = rwLock.read()){
					return super.fromID(rootType, id);
				}
			}
			@Override
			public <T> int toID(Class<T> rootType, Class<T> type, boolean record){
				try(var ignored = rwLock.read()){
					return super.toID(rootType, type, record);
				}
			}
		}
		
		final class Fixed implements MemoryOnlyDB{
			
			private final Map<String, TypeDef> defs;
			
			private final TypeLink[]             idToTyp;
			private final Map<TypeLink, Integer> typToID;
			
			private static class MemUniverse<T>{
				private final Class<T>[]             id2cl;
				private final Map<Class<T>, Integer> cl2id;
				private MemUniverse(Map<Class<T>, Integer> cl2id){
					this.cl2id = Map.copyOf(cl2id);
					var maxId = this.cl2id.values().stream().mapToInt(i -> i).max().orElse(0);
					id2cl = new Class[maxId];
					this.cl2id.forEach((t, i) -> id2cl[i] = t);
				}
			}
			
			private final Map<Class<?>, MemUniverse<?>> sealedMultiverse = new HashMap<>();
			
			private Fixed(Map<String, TypeDef> defs, Map<Integer, TypeLink> idToTyp){
				this.defs = new HashMap<>(defs);
				var maxID = idToTyp.keySet().stream().mapToInt(i -> i).max().orElse(0);
				this.idToTyp = new TypeLink[maxID + 1];
				idToTyp.forEach((k, v) -> this.idToTyp[k] = v.clone());
				typToID = idToTyp.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
			}
			
			private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
			
			@Override
			public TypeID toID(Class<?> type, boolean recordNew){
				return toID(TypeLink.of(type), recordNew);
			}
			
			@Override
			public long typeLinkCount(){ return typToID.size(); }
			@Override
			public long definitionCount(){ return defs.size(); }
			
			@Override
			public TypeID toID(TypeLink type, boolean recordNew){
				var id = typToID.get(type);
				if(id != null) return new TypeID(id, true);
				if(!recordNew) return new TypeID(idToTyp.length, false);
				throw new UnsupportedOperationException();
			}
			
			@Override
			public int toID(Class<?> type){
				return toID(TypeLink.of(type));
			}
			@Override
			public int toID(TypeLink type){
				var id = typToID.get(type);
				if(id != null) return id;
				throw new UnsupportedOperationException();
			}
			
			@Override
			public TypeLink fromID(int id){
				var type = idToTyp(id);
				if(type == null){
					throw new RuntimeException("Unknown type from ID of " + id);
				}
				return type;
			}
			
			private <T> MemUniverse<T> getUniverse(Class<T> rootType){
				//noinspection unchecked
				return (MemUniverse<T>)sealedMultiverse.get(rootType);
			}
			@Override
			public <T> Class<T> fromID(Class<T> rootType, int id){
				if(!rootType.isSealed()) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				if(universe == null) return null;
				if(id>=universe.id2cl.length || id<0) return null;
				return universe.id2cl[id];
			}
			
			@Override
			public <T> int toID(Class<T> rootType, Class<T> type, boolean record){
				if(!rootType.isSealed()) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				return universe.cl2id.get(type);
			}
			
			private TypeLink idToTyp(int id){
				return id>=idToTyp.length || id<0? null : idToTyp[id];
			}
			
			@Override
			public boolean hasType(TypeLink type){
				return typToID.containsKey(type);
			}
			@Override
			public boolean hasID(int id){
				return idToTyp(id) != null;
			}
			
			@Override
			public ClassLoader getTemplateLoader(){
				var l = templateLoader.get();
				if(l == null){
					templateLoader = new WeakReference<>(l = new TemplateClassLoader(this, getClass().getClassLoader()));
				}
				return l;
			}
			
			@Override
			public TypeDef getDefinitionFromClassName(String className){
				if(className == null || className.isEmpty()) return null;
				return defs.get(className);
			}
		}
	}
	
	final class PersistentDB extends IOInstance.Managed<PersistentDB> implements IOTypeDB{
		
		private static final class TypeName extends IOInstance.Managed<TypeName>{
			@IOValue
			private String typeName;
			
			public TypeName(){ }
			public TypeName(String typeName){
				this.typeName = typeName;
			}
			
			@Override
			public String toString(){
				return typeName;
			}
			
			@Override
			public String toShortString(){
				var nam   = typeName;
				var index = nam.lastIndexOf('.');
				if(index != -1) return nam.substring(index + 1);
				return nam;
			}
		}
		
		//Init async, improves first run time, does not load a bunch of classes in static initializer
		private static       int                               FIRST_ID = -1;
		private static final LateInit.Safe<MemoryOnlyDB.Fixed> BUILT_IN = Runner.async(() -> {
			var db = new MemoryOnlyDB.Basic();
			try{
				for(var c : new Class<?>[]{
					byte.class,
					int.class,
					long.class,
					float.class,
					double.class,
					Byte.class,
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
			FIRST_ID = db.maxID();
			return db.bake();
		});
		
		private static void registerBuiltIn(MemoryOnlyDB builtIn, Class<?> c){
			builtIn.toID(c);
			
			for(var dc : c.getDeclaredClasses()){
				if(Modifier.isAbstract(dc.getModifiers()) || !IOInstance.isInstance(dc)) continue;
				registerBuiltIn(builtIn, dc);
			}
			var cl = c;
			while(cl != null && cl != Object.class){
				
				for(var field : cl.getDeclaredFields()){
					if(field.isAnnotationPresent(IOValue.class) && IOInstance.isInstance(field.getType())){
						registerBuiltIn(builtIn, field.getType());
					}
				}
				
				cl = cl.getSuperclass();
			}
		}
		
		@IOValue
		private IOMap<Integer, TypeLink> data;
		
		@IOValue
		private IOMap<TypeName, TypeDef> defs;
		
		private final Map<Integer, TypeLink> dataCache = new HashMap<>();
		private       Map<TypeLink, Integer> reverseDataCache;
		private       int                    max;
		
		private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
		
		@Override
		public long typeLinkCount(){ return data.size(); }
		@Override
		public long definitionCount(){ return defs.size(); }
		
		@Override
		public TypeID toID(TypeLink type, boolean recordNew) throws IOException{
			var builtIn = BUILT_IN.get();
			var id      = builtIn.toID(type, false);
			if(id.stored()) return id;
			
			if(reverseDataCache == null && data.size()<=100000){
				reverseDataCache = HashMap.newHashMap((int)data.size());
				
				int max = FIRST_ID;
				for(var entry : data){
					var key   = entry.getKey();
					var value = entry.getValue();
					reverseDataCache.put(value, key);
					max = Math.max(key, max);
				}
				this.max = max;
			}
			
			int max;
			
			if(reverseDataCache != null){
				var existing = reverseDataCache.get(type);
				if(existing != null){
					return new TypeID(existing, true);
				}
				max = this.max;
			}else{
				max = FIRST_ID;
				for(var entry : data){
					var key = entry.getKey();
					if(entry.getValue().equals(type)){
						return new TypeID(key, true);
					}
					max = Math.max(key, max);
				}
			}
			
			
			var newID = max + 1;
			if(!recordNew) return new TypeID(newID, false);
			
			data.put(newID, type);
			reverseDataCache = null;
			var newDefs = new HashMap<TypeName, TypeDef>();
			recordType(builtIn, type, newDefs);
			
			defs.putAll(newDefs);
			
			if(TYPE_VALIDATION) checkNewTypeValidity(newDefs);
			return new TypeID(newID, true);
		}
		
		private void checkNewTypeValidity(Map<TypeName, TypeDef> newDefs){
			newDefs.entrySet().removeIf(e -> !e.getValue().isIoInstance());
			if(newDefs.isEmpty()) return;
			
			class Validated{
				private static final Set<String> VALS = new HashSet<>();
			}
			synchronized(Validated.VALS){
				newDefs.entrySet().removeIf(e -> Validated.VALS.contains(e.getKey().typeName));
				if(newDefs.isEmpty()) return;
				newDefs.keySet().stream().map(n -> n.typeName).forEach(Validated.VALS::add);
			}
			
			var names = newDefs.entrySet()
			                   .stream()
			                   .filter(e -> !e.getValue().isUnmanaged())
			                   .map(e -> e.getKey().typeName)
			                   .collect(Collectors.toSet());
			
			RuntimeException e = null;
			
			for(var name : names){
				try{
					var cls = Class.forName(
						name, true,
						new TemplateClassLoader(
							this,
							new BlacklistClassLoader(
								false,
								this.getClass().getClassLoader(),
								List.of(name::equals)
							)
						));
					Struct.ofUnknown(cls, StagedInit.STATE_DONE);
				}catch(Throwable ex){
					synchronized(Validated.VALS){
						Validated.VALS.remove(name);
					}
					var e1 = new RuntimeException("Invalid stored class " + name + "\n" + TextUtil.toNamedPrettyJson(newDefs.get(new TypeName(name))), ex);
					if(e == null) e = e1;
					else e.addSuppressed(e1);
				}
			}
			if(e != null) throw e;
		}
		
		private void recordType(MemoryOnlyDB.Fixed builtIn, TypeLink type, Map<TypeName, TypeDef> newDefs) throws IOException{
			var isBuiltIn = builtIn.getDefinitionFromClassName(type.getTypeName()) != null;
			if(isBuiltIn){
				for(int i = 0; i<type.argCount(); i++){
					recordType(builtIn, type.arg(i), newDefs);
				}
				return;
			}
			
			var typeName = new TypeName(type.getTypeName());
			
			var added   = newDefs.containsKey(typeName);
			var defined = defs.containsKey(typeName);
			
			if(added || defined) return;
			
			var typ = type.getTypeClass(null);
			if(typ.isArray()){
				var base = typ;
				while(base.isArray()){
					base = base.componentType();
				}
				if(base.isPrimitive()) return;
				recordType(builtIn, new TypeLink(base), newDefs);
				return;
			}
			
			var def = new TypeDef(typ);
			if(!def.isUnmanaged()){
				newDefs.put(typeName, def);
			}
			
			for(int i = 0; i<type.argCount(); i++){
				recordType(builtIn, type.arg(i), newDefs);
			}
			
			if(def.isUnmanaged()) return;
			
			if(TYPE_VALIDATION){
				StandardStructPipe.of(type.getThisStruct()).checkTypeIntegrity(type, false);
			}
			
			
			for(TypeDef.FieldDef field : def.getFields()){
				recordType(builtIn, field.getType(), newDefs);
			}
		}
		
		@Override
		public TypeLink fromID(int id) throws IOException{
			var builtIn = BUILT_IN.get();
			if(builtIn.hasID(id)){
				return builtIn.fromID(id);
			}
			
			var cached = dataCache.get(id);
			if(cached != null){
				return cached.clone();
			}
			
			var type = data.get(id);
			if(type == null){
				throw new RuntimeException("Unknown type from ID of " + id);
			}
			
			if(dataCache.size()>64){
				dataCache.remove(dataCache.keySet().stream().skip(Rand.i(dataCache.size() - 1)).findAny().orElseThrow());
			}
			dataCache.put(id, type);
			
			
			return type;
		}
		
		@IOValue
		private IOMap<String, IOList<String>> sealedMultiverse;
		
		@Override
		public <T> Class<T> fromID(Class<T> rootType, int id) throws IOException{
			if(!rootType.isSealed()) throw new IllegalArgumentException();
			var universe = sealedMultiverse.get(rootType.getName());
			if(universe == null || id<0 || id>=universe.size()){
				return null;
			}
			var name = universe.get(id);
			if(name == null) return null;
			//noinspection unchecked
			return (Class<T>)loadClass(name);
		}
		
		private final Map<Class<?>, MemoryOnlyDB.Basic.MemUniverse<?>> sealedMultiverseTouch     = new HashMap<>();
		private final ReadWriteClosableLock                            sealedMultiverseTouchLock = ReadWriteClosableLock.reentrant();
		
		@Override
		public <T> int toID(Class<T> rootType, Class<T> type, boolean record) throws IOException{
			if(!rootType.isSealed()) throw new IllegalArgumentException();
			
			try(var ignored = sealedMultiverseTouchLock.read()){
				var touched = getTouched(rootType, type);
				if(touched.isPresent()) return touched.get();
			}
			
			var typeName = type.getName();
			var universe = sealedMultiverse.get(typeName);
			if(universe == null){
				return touch(rootType, type, 0);
			}
			
			var max = Math.toIntExact(universe.size());
			for(int i = 0; i<max; i++){
				var name = universe.get(i);
				if(name.equals(typeName)){
					return i;
				}
			}
			
			return touch(rootType, type, max);
		}
		
		private <T> int touch(Class<T> rootType, Class<T> type, int newId){
			try(var ignored = sealedMultiverseTouchLock.write()){
				var touched = getTouched(rootType, type);
				if(touched.isPresent()) return touched.get();
				@SuppressWarnings("unchecked")
				var touchU =
					(MemoryOnlyDB.Basic.MemUniverse<T>)
						sealedMultiverseTouch.computeIfAbsent(rootType, t -> new MemoryOnlyDB.Basic.MemUniverse<>(newId));
				return touchU.newId(type);
			}
		}
		
		private <T> Optional<Integer> getTouched(Class<T> rootType, Class<T> type){
			return Optional.ofNullable(sealedMultiverseTouch.get(rootType)).map(u -> u.cl2id.get(type));
		}
		
		@Override
		public TypeDef getDefinitionFromClassName(String className) throws IOException{
			var builtIn = BUILT_IN.get();
			if(className == null || className.isEmpty()) return null;
			{
				var def = builtIn.getDefinitionFromClassName(className);
				if(def != null) return def;
			}
			
			return defs.get(new TypeName(className));
		}
		
		public void init(DataProvider provider) throws IOException{
			allocateNulls(provider);
		}
		@Override
		public ClassLoader getTemplateLoader(){
			var l = templateLoader.get();
			if(l == null){
				templateLoader = new WeakReference<>(l = new TemplateClassLoader(this, getClass().getClassLoader()));
			}
			return l;
		}
		
		@Override
		public String toString(){
			return getClass().getSimpleName() + toShortString();
		}
		
		@Override
		public String toShortString(){
			if(data == null) return "{uninitialized}";
			return "{" + data.size() + " " + TextUtil.plural("link", (int)data.size()) + ", " + defs.size() + " class " + TextUtil.plural("definition", (int)defs.size()) + "}";
		}
	}
	
	private TypeLink makeLink(Object obj){
		if(obj instanceof IOInstance.Unmanaged<?> u){
			return u.getTypeDef();
		}
		if(obj instanceof IOInstance.Def<?> u){
			return TypeLink.of(IOInstance.Def.unmap(u.getClass()).orElseThrow());
		}
		return TypeLink.of(obj.getClass());
	}
	
	default int toID(Object obj) throws IOException{
		if(obj == null) return 0;
		return toID(makeLink(obj));
	}
	default TypeID toID(Object obj, boolean recordNew) throws IOException{
		if(obj == null) return new TypeID(0, true);
		var link = makeLink(obj);
		return toID(link, recordNew);
	}
	
	default int toID(Class<?> type) throws IOException{
		return toID(TypeLink.of(type), true).requireStored();
	}
	default int toID(TypeLink type) throws IOException{
		return toID(type, true).requireStored();
	}
	
	default TypeID toID(Class<?> type, boolean recordNew) throws IOException{
		return toID(TypeLink.of(type), recordNew);
	}
	
	record TypeID(int val, boolean stored){
		int requireStored(){
			if(!stored) throw new IllegalStateException("ID not stored");
			return val;
		}
	}
	
	long typeLinkCount();
	long definitionCount();
	
	TypeID toID(TypeLink type, boolean recordNew) throws IOException;
	TypeLink fromID(int id) throws IOException;
	
	
	<T> Class<T> fromID(Class<T> rootType, int id) throws IOException;
	<T> int toID(Class<T> rootType, Class<T> type, boolean record) throws IOException;
	
	TypeDef getDefinitionFromClassName(String className) throws IOException;
	
	ClassLoader getTemplateLoader();
	
	
	default Class<?> loadClass(String name){
		try{
			return Class.forName(name);
		}catch(ClassNotFoundException e){
			Log.trace("Loading template: {}#yellow", name);
			try{
				return Class.forName(name, true, getTemplateLoader());
			}catch(ClassNotFoundException ex){
				throw new RuntimeException(ex);
			}
		}
	}
}
