package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.MissingRoot;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOType;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.IterablePP;
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
		
		
		public Builder<T> withId(AutoText id){
			return withId(new ObjectID(id.getData()));
		}
		public Builder<T> withId(String id){
			return withId(new ObjectID(id));
		}
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
						
						var inst = uStruct.make(provider, mem.getPtr().makeReference(), genericType);
						return (T)inst;
					});
				}else{
					return withGenerator(() -> {
						var inst = struct.make();
						if(struct.hasInvalidInitialNulls()){
							inst.allocateNulls(provider, struct.describeGenerics(genericType));
						}
						return (T)inst;
					});
				}
			}
			
			if(IOType.getArgs(genericType).isEmpty()){
				throw new IllegalStateException(rawType.getName() + " should not be generic");
			}
			
			var p = SupportedPrimitive.get(rawType).map(typ -> withGenerator(() -> (T)typ.getDefaultValue()));
			if(p.isPresent()) return p.get();
			
			if(rawType.isEnum()){
				var universe = EnumUniverse.ofUnknown(rawType);
				if(universe.isEmpty()) throw new IllegalArgumentException();
				return withGenerator(() -> (T)universe.get(0));
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
	
	default <T> Builder<T> builder(){
		return new Builder<>(this);
	}
	default <T> Builder<T> builder(String id){ return new Builder<T>(this).withId(id); }
	
	default <T> T require(String id, Class<T> type) throws IOException{
		var val = builder(id).withGenerator(() -> {
			throw new MissingRoot(id + " does not exist!");
		}).request();
		return type.cast(val);
	}
	
	default <T> T request(String id, Class<?> raw, Class<?>... args) throws IOException      { return this.<T>builder(id).withType(IOType.of(raw, args)).request(); }
	default <T extends IOInstance<T>> T request(String id, Struct<T> type) throws IOException{ return this.builder(id).withType(type.getType()).request(); }
	default <T> T request(String id, Class<T> type) throws IOException                       { return this.builder(id).withType(type).request(); }
	
	<T> T request(ObjectID id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException;
	default <T> void provide(String id, T obj) throws IOException{
		provide(new ObjectID(id), obj);
	}
	<T> void provide(ObjectID id, T obj) throws IOException;
	
	
	IterablePP<IOMap.IOEntry<ObjectID, Object>> listAll();
	
	default void drop(String id) throws IOException{
		drop(new ObjectID(id));
	}
	void drop(ObjectID id) throws IOException;
}
