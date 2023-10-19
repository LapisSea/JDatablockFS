package com.lapissea.dfs.type;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.chunk.AllocateTicket;
import com.lapissea.dfs.chunk.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.compilation.TemplateClassLoader;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.util.LateInit;
import com.lapissea.util.Rand;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.config.GlobalConfig.TYPE_VALIDATION;


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
		TypeID toID(IOType type, boolean recordNew);
		@Override
		int toID(Class<?> type);
		@Override
		int toID(IOType type);
		
		@Override
		IOType fromID(int id);
		boolean hasType(IOType type);
		boolean hasID(int id);
		
		@Override
		OptionalPP<TypeDef> getDefinitionFromClassName(String className);
		
		sealed class Basic implements MemoryOnlyDB{
			
			private final Map<String, TypeDef> defs = new HashMap<>();
			
			private final Map<Integer, IOType> idToTyp = new HashMap<>();
			private final Map<IOType, Integer> typToID = new HashMap<>();
			private       int                  maxID   = 0;
			
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
				return toID(IOType.of(type), recordNew);
			}
			
			@Override
			public long typeLinkCount(){ return typToID.size(); }
			@Override
			public long definitionCount(){ return defs.size(); }
			
			@Override
			public TypeID toID(IOType type, boolean recordNew){
				var id = typToID.get(type);
				if(id != null) return new TypeID(id, true);
				return newID(type, recordNew);
			}
			
			@Override
			public int toID(Class<?> type){
				return toID(IOType.of(type));
			}
			@Override
			public int toID(IOType type){
				var id = typToID.get(type);
				if(id != null) return id;
				return newID(type, true).requireStored();
			}
			
			protected TypeID newID(IOType type, boolean recordNew){
				if(typToID.containsKey(type)){
					throw new IllegalArgumentException(type + " is already registered");
				}
				var newID = maxID() + 1;
				if(!recordNew) return new TypeID(newID, false);
				idToTyp.put(newID, type);
				typToID.put(type, newID);
				maxID = newID;
				
				
				recordType(type);
				return new TypeID(newID, true);
			}
			
			private void recordType(IOType type){
				for(var typeRaw : type.collectRaws(this)){
					if(!defs.containsKey(typeRaw.getName())){
						var def = new TypeDef(type.getTypeClass(this));
						if(!def.isUnmanaged()){
							defs.putIfAbsent(typeRaw.getName(), def);
						}else{
							defs.put(typeRaw.getName(), null);
						}
						
						for(TypeDef.FieldDef field : def.getFields()){
							recordType(field.getType());
						}
					}
				}
			}
			
			@Override
			public IOType fromID(int id){
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
				if(!isSealedCached(rootType)) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				return universe.id2cl.get(id);
			}
			
			@Override
			public <T> int toID(Class<T> rootType, Class<T> type, boolean record){
				if(!isSealedCached(rootType)) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				var id       = universe.cl2id.get(type);
				if(id == null){
					id = universe.newId(type);
				}
				return id;
			}
			
			@Override
			public boolean hasType(IOType type){
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
			public OptionalPP<TypeDef> getDefinitionFromClassName(String className){
				if(className == null || className.isEmpty()) return OptionalPP.empty();
				return OptionalPP.ofNullable(defs.get(className));
			}
			
			public Fixed bake(){
				return new Fixed(defs, idToTyp, sealedMultiverse);
			}
		}
		
		final class Synchronized extends Basic{
			
			@Override
			public int toID(IOType type){
				synchronized(this){
					return super.toID(type);
				}
			}
			
			@Override
			protected TypeID newID(IOType type, boolean recordNew){
				synchronized(this){
					return super.newID(type, recordNew);
				}
			}
			
			@Override
			public IOType fromID(int id){
				synchronized(this){
					return super.fromID(id);
				}
			}
			
			@Override
			public boolean hasType(IOType type){
				synchronized(this){
					return super.hasType(type);
				}
			}
			@Override
			public boolean hasID(int id){
				synchronized(this){
					return super.hasID(id);
				}
			}
			@Override
			public <T> Class<T> fromID(Class<T> rootType, int id){
				synchronized(this){
					return super.fromID(rootType, id);
				}
			}
			@Override
			public <T> int toID(Class<T> rootType, Class<T> type, boolean record){
				synchronized(this){
					return super.toID(rootType, type, record);
				}
			}
		}
		
		final class Fixed implements MemoryOnlyDB{
			
			private final Map<String, TypeDef> defs;
			
			private final IOType[]             idToTyp;
			private final Map<IOType, Integer> typToID;
			
			private static final class MemUniverse<T>{
				private final Class<T>[]             id2cl;
				private final Map<Class<T>, Integer> cl2id;
				private MemUniverse(Map<Class<T>, Integer> cl2id){
					this.cl2id = Map.copyOf(cl2id);
					var maxId = this.cl2id.values().stream().mapToInt(i -> i + 1).max().orElse(0);
					id2cl = new Class[maxId];
					this.cl2id.forEach((t, i) -> id2cl[i] = t);
				}
			}
			
			private final Map<Class<?>, MemUniverse<?>> sealedMultiverse;
			
			private Fixed(Map<String, TypeDef> defs, Map<Integer, IOType> idToTyp, Map<Class<?>, Basic.MemUniverse<?>> sealedMultiverse){
				this.defs = new HashMap<>(defs);
				var maxID = idToTyp.keySet().stream().mapToInt(i -> i).max().orElse(0);
				this.idToTyp = new IOType[maxID + 1];
				idToTyp.forEach((k, v) -> this.idToTyp[k] = v.clone());
				typToID = idToTyp.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
				this.sealedMultiverse = sealedMultiverse.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, u -> new MemUniverse<>(u.getValue().cl2id)));
			}
			
			private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
			
			@Override
			public TypeID toID(Class<?> type, boolean recordNew){
				return toID(IOType.of(type), recordNew);
			}
			
			@Override
			public long typeLinkCount(){ return typToID.size(); }
			@Override
			public long definitionCount(){ return defs.size(); }
			
			@Override
			public TypeID toID(IOType type, boolean recordNew){
				var id = typToID.get(type);
				if(id != null) return new TypeID(id, true);
				if(!recordNew) return new TypeID(idToTyp.length, false);
				throw new UnsupportedOperationException();
			}
			
			@Override
			public int toID(Class<?> type){
				return toID(IOType.of(type));
			}
			@Override
			public int toID(IOType type){
				var id = typToID.get(type);
				if(id != null) return id;
				throw new UnsupportedOperationException();
			}
			
			@Override
			public IOType fromID(int id){
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
				if(!isSealedCached(rootType)) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				if(universe == null) return null;
				if(id>universe.id2cl.length || id<=0) return null;
				return universe.id2cl[id];
			}
			
			@Override
			public <T> int toID(Class<T> rootType, Class<T> type, boolean record){
				if(!isSealedCached(rootType)) throw new IllegalArgumentException();
				var universe = getUniverse(rootType);
				if(universe == null) return -1;
				return universe.cl2id.get(type);
			}
			
			private IOType idToTyp(int id){
				return id>=idToTyp.length || id<0? null : idToTyp[id];
			}
			
			@Override
			public boolean hasType(IOType type){
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
			public OptionalPP<TypeDef> getDefinitionFromClassName(String className){
				if(className == null || className.isEmpty()) return OptionalPP.empty();
				return OptionalPP.ofNullable(defs.get(className));
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
		private static final LateInit.Safe<MemoryOnlyDB.Fixed> BUILT_IN = new LateInit.Safe<>(() -> {
			//TODO: there is a deadlock of some sort involving virtual threads and a low core count. Investigate
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
					
					Reference.class,
					}){
					db.newID(IOType.of(c), true);
				}
				for(Class<?> wrapperType : FieldCompiler.getWrapperTypes()){
					db.newID(IOType.of(wrapperType), true);
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
				
				var uni = SealedUtil.getSealedUniverse(IOType.class, false).orElseThrow();
				uni.universe().stream().sorted(Comparator.comparing(Class::getName)).forEach(cls -> {
					db.toID(uni.root(), cls, true);
				});
			}catch(Throwable e){
				e.printStackTrace();
				throw new RuntimeException("Failed to initialize built in type IDs", e);
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
					if(IOFieldTools.isIOField(field) && IOInstance.isInstance(field.getType())){
						var typ = field.getType();
						if(!builtIn.hasType(IOType.of(typ))){
							registerBuiltIn(builtIn, typ);
						}
					}
				}
				
				cl = cl.getSuperclass();
			}
		}
		
		@IOValue
		private IOMap<Integer, IOType> data;
		
		@IOValue
		private IOMap<TypeName, TypeDef> defs;
		
		private final Map<Integer, IOType> dataCache = new HashMap<>();
		private       Map<IOType, Integer> reverseDataCache;
		private       int                  max;
		
		private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
		
		@Override
		public long typeLinkCount(){ return data.size(); }
		@Override
		public long definitionCount(){ return defs.size(); }
		
		@Override
		public TypeID toID(IOType type, boolean recordNew) throws IOException{
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
			try{
				recordType(List.of(type));
			}catch(Throwable e){
				throw new RuntimeException("Failed to record " + type, e);
			}
			return new TypeID(newID, true);
		}
		
		private void recordType(List<IOType> types) throws IOException{
			var builtIn = BUILT_IN.get();
			var newDefs = new HashMap<TypeName, TypeDef>();
			for(var type : types){
				recordType(builtIn, type, newDefs);
			}
			
			defs.putAll(newDefs);
			
			if(TYPE_VALIDATION) checkNewTypeValidity(newDefs);
		}
		
		public Set<String> listStoredTypeDefinitionNames(){
			return defs.stream().map(k -> k.getKey().typeName).collect(Collectors.toSet());
		}
		
		private void checkNewTypeValidity(Map<TypeName, TypeDef> newDefs) throws IOException{
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
			
			Function<StructPipe<?>, List<String>> toNames =
				pipe -> pipe.getSpecificFields().stream()
				            .map(IOField::getName)
				            .toList();
			
			var fieldMap =
				newDefs.entrySet()
				       .stream()
				       .filter(e -> e.getValue().isIoInstance() && !e.getValue().isUnmanaged())
				       .map(e -> {
					       Struct<?> typ;
					       try{
						       var cls = Class.forName(e.getKey().typeName);
						       typ = Struct.ofUnknown(cls);
					       }catch(ClassNotFoundException ex){
						       throw new RuntimeException(ex);
					       }catch(IllegalArgumentException ex){
						       return null;
					       }
					       return Map.entry(e.getKey(), typ);
				       })
				       .filter(Objects::nonNull)
				       .collect(Collectors.toMap(e -> e.getKey().typeName, e -> {
					       var typ  = e.getValue();
					       var pipe = StandardStructPipe.of(typ);
					       return toNames.apply(pipe);
				       }));
			
			RuntimeException e = null;
			
			
			Set<String> containedKeys;
			try{
				containedKeys = defs.stream().map(IOMap.IOEntry::getKey).map(t -> t.typeName).collect(Collectors.toUnmodifiableSet());
			}catch(Throwable e1){
				throw new IOException("Failed to read def keys", e1);
			}
			
			for(var name : names){
				Log.trace("Checking validity of {}#blueBright", name);
				try{
					var cls = Class.forName(
						name, true,
						new TemplateClassLoader(
							this,
							new BlacklistClassLoader(
								false,
								this.getClass().getClassLoader(),
								List.of(names::contains, containedKeys::contains)
							)
						));
					if(fieldMap.containsKey(name) && IOInstance.isManaged(cls)){
						var pipe               = StandardStructPipe.of(Struct.ofUnknown(cls));
						var actualFieldNames   = toNames.apply(pipe);
						var expectedFieldNames = fieldMap.get(name);
						if(!actualFieldNames.equals(expectedFieldNames)){
							var table = new ArrayList<Map<String, String>>();
							for(int i = 0; i<Math.max(actualFieldNames.size(), expectedFieldNames.size()); i++){
								var map = LinkedHashMap.<String, String>newLinkedHashMap(2);
								if(actualFieldNames.size()>i){
									map.put("Actual", actualFieldNames.get(i));
								}
								if(expectedFieldNames.size()>i){
									map.put("Expected", expectedFieldNames.get(i));
								}
								table.add(map);
							}
							
							throw new RuntimeException(
								"Field order not preserved:\n" +
								TextUtil.toTable(table)
							);
						}
					}
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
		
		private void recordType(MemoryOnlyDB.Fixed builtIn, IOType type, Map<TypeName, TypeDef> newDefs) throws IOException{
			if(type instanceof IOType.TypeNameArg arg){
				var raws = arg.collectRaws(this);
				for(var raw : raws){
					recordType(builtIn, raw, newDefs);
				}
				return;
			}
			
			var isBuiltIn = builtIn.getDefinitionFromClassName(type.getTypeName()).isPresent();
			if(isBuiltIn){
				for(IOType arg : IOType.getArgs(type)){
					recordType(builtIn, arg, newDefs);
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
				recordType(builtIn, IOType.of(base), newDefs);
				return;
			}
			
			var def    = new TypeDef(typ);
			var parent = def.getSealedParent();
			if(parent != null){
				recordType(builtIn, IOType.of(switch(parent.type()){
					case EXTEND -> typ.getSuperclass();
					case JUST_INTERFACE -> Arrays.stream(typ.getInterfaces())
					                             .filter(i -> i.getName().equals(parent.name()))
					                             .findAny().orElseThrow();
				}), newDefs);
			}
			
			if(!def.isUnmanaged() && (def.isIoInstance() || def.isEnum() || def.isJustInterface())){
				newDefs.put(typeName, def);
			}
			
			for(IOType arg : IOType.getArgs(type)){
				recordType(builtIn, arg, newDefs);
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
		public IOType fromID(int id) throws IOException{
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
		private HashIOMap<String, IOList<String>> sealedMultiverse;
		
		private final Map<Class<?>, MemoryOnlyDB.Basic.MemUniverse<?>> sealedMultiverseTouch = new HashMap<>();
		
		@Override
		public <T> Class<T> fromID(Class<T> rootType, int id) throws IOException{
			if(!isSealedCached(rootType)) throw new IllegalArgumentException();
			
			var builtIn = BUILT_IN.get();
			var bcls    = builtIn.fromID(rootType, id);
			if(bcls != null) return bcls;
			
			if(id<=0){
				return null;
			}
			
			var touched = getTouchedUniverse(rootType).map(u -> u.id2cl.get(id));
			if(touched.isPresent()){
				return touched.get();
			}
			
			var universe = sealedMultiverse.get(rootType.getName());
			if(universe == null || id>universe.size()){
				return null;
			}
			var name = universe.get(id - 1);
			if(name == null) return null;
			//noinspection unchecked
			return (Class<T>)loadClass(name);
		}
		
		@Override
		public <T> int toID(Class<T> rootType, Class<T> type, boolean record) throws IOException{
			var builtIn = BUILT_IN.get();
			var bid     = builtIn.toID(rootType, type, false);
			if(bid != -1) return bid;
			
			
			if(!isSealedCached(rootType)) throw new IllegalArgumentException();
			
			var touched = getTouched(rootType, type);
			if(touched.isPresent()){
				if(record){
					recordTouched(rootType);
				}
				return touched.get();
			}
			
			
			var typeName     = type.getName();
			var rootTypeName = rootType.getName();
			var universe     = record? requireIOUniverse(rootTypeName) : sealedMultiverse.get(rootTypeName);
			if(universe == null){
				return touch(rootType, type, 1);
			}
			
			var max = Math.toIntExact(universe.size());
			for(int i = 0; i<max; i++){
				var name = universe.get(i);
				if(name.equals(typeName)){
					return i + 1;
				}
			}
			
			if(record){
				recordType(List.of(IOType.of(type)));
				universe.add(typeName);
				return max + 1;
			}
			
			return touch(rootType, type, max + 1);
		}
		
		private <T> void recordTouched(Class<T> rootType) throws IOException{
			//noinspection unchecked
			var universe = (MemoryOnlyDB.Basic.MemUniverse<T>)sealedMultiverseTouch.remove(rootType);
			if(universe != null){
				recordType(universe.id2cl.values().stream().map(IOType::of).toList());
				
				IOList<String> ioUniverse = requireIOUniverse(rootType.getName());
				for(var e : universe.id2cl.entrySet()){
					int idx = e.getKey();
					var cls = e.getValue().getName();
					
					if(ioUniverse.size()>idx){
						failRegister(cls, idx, ioUniverse);
					}else{
						ioUniverse.add(cls);
					}
				}
			}
		}
		private static void failRegister(String cls, int idx, IOList<String> ioUniverse) throws IOException{
			var sb = new StringBuilder("Tried to register " + cls + " on " + idx + " but there is:\n");
			for(long i = 0; i<ioUniverse.size(); i++){
				sb.append(i).append("\t-> ").append(ioUniverse.get(i)).append('\n');
			}
			throw new IllegalStateException(sb.toString());
		}
		
		private IOList<String> requireIOUniverse(String rootTypeName) throws IOException{
			var sm = this.sealedMultiverse;
			return sm.computeIfAbsent(rootTypeName, () -> {
				var ch = AllocateTicket.bytes(16)
				                       .withPositionMagnet(sm.getReference().getPtr().getValue())
				                       .submit(sm);
				var type = IOType.of(ContiguousIOList.class, String.class);
				return new ContiguousIOList<>(sm.getDataProvider(), ch.getPtr().makeReference(), type);
			});
		}
		
		private <T> int touch(Class<T> rootType, Class<T> type, int newId){
			var touched = getTouched(rootType, type);
			if(touched.isPresent()) return touched.get();
			@SuppressWarnings("unchecked")
			var touchU =
				(MemoryOnlyDB.Basic.MemUniverse<T>)
					sealedMultiverseTouch.computeIfAbsent(rootType, t -> new MemoryOnlyDB.Basic.MemUniverse<>(newId));
			return touchU.newId(type);
		}
		
		@SuppressWarnings("unchecked")
		private <T> Optional<MemoryOnlyDB.Basic.MemUniverse<T>> getTouchedUniverse(Class<T> rootType){
			return Optional.ofNullable((MemoryOnlyDB.Basic.MemUniverse<T>)sealedMultiverseTouch.get(rootType));
		}
		
		private <T> Optional<Integer> getTouched(Class<T> rootType, Class<T> type){
			var un = getTouchedUniverse(rootType);
			return un.map(u -> u.cl2id.get(type));
		}
		
		@Override
		public OptionalPP<TypeDef> getDefinitionFromClassName(String className) throws IOException{
			if(className == null || className.isEmpty()) return OptionalPP.empty();
			return BUILT_IN.get().getDefinitionFromClassName(className).or(() -> {
				return OptionalPP.ofNullable(defs.get(new TypeName(className)));
			});
		}
		
		public void init(DataProvider provider) throws IOException{
			allocateNulls(provider, null);
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
	
	private IOType makeType(Object obj){
		if(obj instanceof IOInstance.Unmanaged<?> u){
			return u.getTypeDef();
		}
		if(obj instanceof IOInstance.Def<?> u){
			return IOType.of(IOInstance.Def.unmap(u.getClass()).orElseThrow());
		}
		return IOType.of(obj.getClass());
	}
	
	default int toID(Object obj) throws IOException{
		if(obj == null) return 0;
		return toID(makeType(obj));
	}
	default TypeID toID(Object obj, boolean recordNew) throws IOException{
		if(obj == null) return new TypeID(0, true);
		var type = makeType(obj);
		return toID(type, recordNew);
	}
	
	default int toID(Class<?> type) throws IOException{
		return toID(IOType.of(type), true).requireStored();
	}
	default int toID(IOType type) throws IOException{
		return toID(type, true).requireStored();
	}
	
	default TypeID toID(Class<?> type, boolean recordNew) throws IOException{
		return toID(IOType.of(type), recordNew);
	}
	
	record TypeID(int val, boolean stored){
		int requireStored(){
			if(!stored) throw new IllegalStateException("ID not stored");
			return val;
		}
	}
	
	long typeLinkCount();
	long definitionCount();
	
	TypeID toID(IOType type, boolean recordNew) throws IOException;
	IOType fromID(int id) throws IOException;
	
	
	<T> Class<T> fromID(Class<T> rootType, int id) throws IOException;
	<T> int toID(Class<T> rootType, Class<T> type, boolean record) throws IOException;
	
	OptionalPP<TypeDef> getDefinitionFromClassName(String className) throws IOException;
	
	ClassLoader getTemplateLoader();
	
	
	default Class<?> loadClass(String name){
		try{
			return Class.forName(name);
		}catch(ClassNotFoundException e){
			try{
				return Class.forName(name, true, getTemplateLoader());
			}catch(ClassNotFoundException ex){
				throw new RuntimeException(ex);
			}
		}
	}
}
