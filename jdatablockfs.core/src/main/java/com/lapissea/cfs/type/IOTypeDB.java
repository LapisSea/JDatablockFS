package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.type.compilation.TemplateClassLoader;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.ReadWriteClosableLock;
import com.lapissea.util.LateInit;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.TYPE_VALIDATION;


/**
 * This interface provides a simple protocol to convert a lengthy type definition in to an ID and back.
 * The implementation is expected to be used mainly as a way to remove excessive repeating when
 * interacting with a value of unknown implicit type.
 */
public sealed interface IOTypeDB{
	final class MemoryOnlyDB implements IOTypeDB{
		
		private final ReadWriteClosableLock rwLock = ReadWriteClosableLock.reentrant();
		
		private final Map<String, TypeDef> defs = new HashMap<>();
		
		private final Map<Integer, TypeLink> idToTyp = new HashMap<>();
		private final Map<TypeLink, Integer> typToID = new HashMap<>();
		private       int                    maxID   = 0;
		
		private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
		
		@Override
		public TypeID toID(Class<?> type, boolean recordNew){
			return toID(TypeLink.of(type), recordNew);
		}
		
		@Override
		public TypeID toID(TypeLink type, boolean recordNew){
			try(var ignored = rwLock.read()){
				var id = typToID.get(type);
				if(id != null) return new TypeID(id, true);
			}
			return newID(type, recordNew);
		}
		
		private TypeID newID(TypeLink type, boolean recordNew){
			try(var ignored = rwLock.write()){
				var newID = maxID() + 1;
				if(!recordNew) return new TypeID(newID, false);
				idToTyp.put(newID, type);
				typToID.put(type, newID);
				maxID = newID;
				
				recordType(type);
				return new TypeID(newID, true);
			}
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
			try(var ignored = rwLock.read()){
				var type = idToTyp.get(id);
				if(type == null){
					throw new RuntimeException("Unknown type from ID of " + id);
				}
				return type;
			}
		}
		
		public boolean hasType(TypeLink type){
			try(var ignored = rwLock.read()){
				return typToID.containsKey(type);
			}
		}
		public boolean hasID(int id){
			try(var ignored = rwLock.read()){
				return idToTyp.containsKey(id);
			}
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
		private static       int                         FIRST_ID = -1;
		private static final LateInit.Safe<MemoryOnlyDB> BUILT_IN = Runner.async(() -> {
			var db = new MemoryOnlyDB();
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
			return db;
		});
		
		private static void registerBuiltIn(MemoryOnlyDB builtIn, Class<?> c){
			builtIn.toID(c, true);
			
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
		
		private WeakReference<ClassLoader> templateLoader = new WeakReference<>(null);
		
		@Override
		public TypeID toID(TypeLink type, boolean recordNew) throws IOException{
			var builtIn = BUILT_IN.get();
			var id      = builtIn.toID(type, false);
			if(id.stored()) return id;
			
			int max = 0;
			for(var entry : data){
				var key = entry.getKey();
				if(entry.getValue().equals(type)){
					return new TypeID(key, true);
				}
				max = Math.max(key, max);
			}
			
			max = Math.max(FIRST_ID, max);
			
			var newID = max + 1;
			if(!recordNew) return new TypeID(newID, false);
			
			data.put(newID, type);
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
		
		private void recordType(MemoryOnlyDB builtIn, TypeLink type, Map<TypeName, TypeDef> newDefs) throws IOException{
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
			
			var type = data.get(id);
			if(type == null){
				throw new RuntimeException("Unknown type from ID of " + id);
			}
			
			return type;
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
	
	default TypeID toID(Class<?> type, boolean recordNew) throws IOException{
		return toID(TypeLink.of(type), recordNew);
	}
	
	record TypeID(int val, boolean stored){ }
	
	TypeID toID(TypeLink type, boolean recordNew) throws IOException;
	TypeLink fromID(int id) throws IOException;
	
	TypeDef getDefinitionFromClassName(String className) throws IOException;
	
	ClassLoader getTemplateLoader();
}
