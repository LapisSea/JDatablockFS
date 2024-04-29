package com.lapissea.dfs.core;

import com.lapissea.dfs.exceptions.MissingRoot;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unused")
public interface RootProvider extends DataProvider.Holder{
	
	final class Builder<T>{
		
		private final RootProvider provider;
		
		private final ObjectID                       id;
		private final UnsafeSupplier<T, IOException> objectGenerator;
		
		public Builder(RootProvider provider){
			this.provider = provider;
			id = null;
			objectGenerator = null;
		}
		
		private Builder(RootProvider provider, ObjectID id, UnsafeSupplier<T, IOException> objectGenerator){
			this.provider = provider;
			this.id = id;
			this.objectGenerator = objectGenerator;
		}
		
		
		public Builder<T> withId(byte id)  { return withId(ObjectID.of(id)); }
		public Builder<T> withId(long id)  { return withId(ObjectID.of(id)); }
		public Builder<T> withId(String id){ return withId(ObjectID.of(id)); }
		public Builder<T> withId(ObjectID id){
			return new Builder<>(provider, id, objectGenerator);
		}
		
		@SuppressWarnings("unchecked")
		public <CT> Builder<? extends CT> withType(Class<CT> genericType, Type... args){ return (Builder<CT>)withType(IOType.of(genericType, args)); }
		@SuppressWarnings("unchecked")
		public <CT> Builder<CT> withType(Class<CT> genericType){ return (Builder<CT>)withType(IOType.of(genericType)); }
		public Builder<T> withType(Type genericType){ return withType(Objects.requireNonNull(IOType.of(genericType))); }
		@SuppressWarnings("unchecked")
		public Builder<T> withType(IOType genericType){
			var provider = this.provider.getDataProvider();
			var rawType  = genericType.getTypeClass(provider.getTypeDb());
			
			if(!IOInstance.isInstance(rawType)){
				var defTypAnn = rawType.getAnnotation(IOValue.OverrideType.DefaultImpl.class);
				if(defTypAnn != null){
					var defTyp = defTypAnn.value();
					if(!IOInstance.isInstance(defTyp)) throw new IllegalStateException();
					List<Type> args = switch(genericType){
						case IOType.TypeGeneric g -> g.genericArgs(provider.getTypeDb());
						default -> List.of();
					};
					
					return withType(IOType.of(defTypAnn.value(), args));
				}
			}
			
			if(IOInstance.isInstance(rawType)){
				var struct = Struct.ofUnknown(rawType);
				
				if(struct instanceof Struct.Unmanaged<?> uStruct){
					return withGenerator(() -> {
						var pipe = StandardStructPipe.of(struct);
						var siz  = pipe.getSizeDescriptor().calcAllocSize(WordSpace.BYTE);
						
						var mem = AllocateTicket.bytes(siz).submit(provider);
						
						var inst = uStruct.make(provider, mem, genericType);
						return (T)inst;
					});
				}else{
					return withGenerator(() -> {
						var inst = struct.make();
						if(struct.hasInvalidInitialNulls()){
							inst.allocateNulls(provider, struct.describeGenerics(genericType, provider.getTypeDb()));
						}
						return (T)inst;
					});
				}
			}
			
			if(!IOType.getArgs(genericType).isEmpty()){
				throw new IllegalStateException(rawType.getName() + " should not be generic but is: " + genericType);
			}
			
			var p = SupportedPrimitive.get(rawType).map(typ -> withGenerator(() -> (T)typ.getDefaultValue()));
			if(p.isPresent()) return p.get();
			
			if(rawType.isEnum()){
				var universe = EnumUniverse.ofUnknown(rawType);
				if(universe.isEmpty()) throw new IllegalArgumentException();
				return withGenerator(() -> (T)universe.getFirst());
			}
			
			if(rawType == String.class){
				return withGenerator(() -> (T)"");
			}
			
			throw new IllegalArgumentException("Unrecognised type: " + rawType.getSimpleName() + " in " + genericType);
		}
		
		public <CT> Builder<CT> withGenerator(UnsafeSupplier<CT, IOException> objectGenerator){
			return new Builder<>(provider, id, objectGenerator);
		}
		
		public T request() throws IOException{
			return provider.request(id, objectGenerator);
		}
	}
	
	default <T> Builder<T> builder(String id)  { return builder(ObjectID.of(id)); }
	default <T> Builder<T> builder(long id)    { return builder(ObjectID.of(id)); }
	default <T> Builder<T> builder(byte id)    { return builder(ObjectID.of(id)); }
	default <T> Builder<T> builder(ObjectID id){ return new Builder<T>(this).withId(id); }
	default <T> Builder<T> builder(){
		return new Builder<>(this);
	}
	
	default <T extends IOInstance.Unmanaged<T>> T require(ObjectID id, Class<T> type, Class<?>... typeArgs) throws IOException{
		var val        = require(id, type);
		var typeDef    = val.getTypeDef();
		var actualArgs = IOType.getArgs(typeDef);
		if(actualArgs.size() != typeArgs.length){
			throw new IllegalArgumentException("Expected " + actualArgs.size() + " args for " + type.getName());
		}
		var db = getDataProvider().getTypeDb();
		for(int i = 0; i<typeArgs.length; i++){
			var expected = typeArgs[i];
			var actual   = actualArgs.get(i).getTypeClass(db);
			if(UtilL.instanceOf(actual, expected)) continue;
			throw new ClassCastException("Incompatible type: " + actual.getName() + " can not be cast to " + expected.getName());
		}
		
		return val;
	}
	
	default <T> T require(long id, Class<T> type) throws IOException  { return require(ObjectID.of(id), type); }
	default <T> T require(byte id, Class<T> type) throws IOException  { return require(ObjectID.of(id), type); }
	default <T> T require(String id, Class<T> type) throws IOException{ return require(ObjectID.of(id), type); }
	default <T> T require(ObjectID id, Class<T> type) throws IOException{
		var val = builder(id).withGenerator(() -> {
			throw new MissingRoot(id + " does not exist!");
		}).request();
		//noinspection unchecked
		var t = SupportedPrimitive.get(type)
		                          .map(p -> (Class<T>)p.wrapper)
		                          .orElse(type);
		return t.cast(val);
	}
	
	default <T> T request(long id, Class<?> raw, Class<?>... args) throws IOException                  { return request(ObjectID.of(id), raw, args); }
	default <T> T request(byte id, Class<?> raw, Class<?>... args) throws IOException                  { return request(ObjectID.of(id), raw, args); }
	default <T> T request(String id, Class<?> raw, Class<?>... args) throws IOException                { return request(ObjectID.of(id), raw, args); }
	default <T> T request(ObjectID id, Class<?> raw, Class<?>... args) throws IOException              { return this.<T>builder(id).withType(IOType.of(raw, args)).request(); }
	
	default <T extends IOInstance<T>> T request(long id, Struct<T> type) throws IOException            { return request(ObjectID.of(id), type); }
	default <T extends IOInstance<T>> T request(byte id, Struct<T> type) throws IOException            { return request(ObjectID.of(id), type); }
	default <T extends IOInstance<T>> T request(String id, Struct<T> type) throws IOException          { return request(ObjectID.of(id), type); }
	default <T extends IOInstance<T>> T request(ObjectID id, Struct<T> type) throws IOException        { return this.builder(id).withType(type.getType()).request(); }
	
	default <T> T request(long id, Class<T> type) throws IOException                                   { return request(ObjectID.of(id), type); }
	default <T> T request(byte id, Class<T> type) throws IOException                                   { return request(ObjectID.of(id), type); }
	default <T> T request(String id, Class<T> type) throws IOException                                 { return request(ObjectID.of(id), type); }
	default <T> T request(ObjectID id, Class<T> type) throws IOException                               { return this.builder(id).withType(type).request(); }
	
	default <T> T request(long id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException  { return request(ObjectID.of(id), objectGenerator); }
	default <T> T request(byte id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException  { return request(ObjectID.of(id), objectGenerator); }
	default <T> T request(String id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException{ return request(ObjectID.of(id), objectGenerator); }
	<T> T request(ObjectID id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException;
	
	default <T> void provide(long id, T obj) throws IOException                                        { provide(ObjectID.of(id), obj); }
	default <T> void provide(byte id, T obj) throws IOException                                        { provide(ObjectID.of(id), obj); }
	default <T> void provide(String id, T obj) throws IOException                                      { provide(ObjectID.of(id), obj); }
	<T> void provide(ObjectID id, T obj) throws IOException;
	
	
	IterablePP<IOMap.IOEntry<ObjectID, Object>> listAll();
	
	default void drop(String id) throws IOException{
		drop(ObjectID.of(id));
	}
	void drop(ObjectID id) throws IOException;
}
