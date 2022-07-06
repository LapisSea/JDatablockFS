package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.*;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Objects;

public interface RootProvider extends DataProvider.Holder{
	
	final class Builder<T>{
		
		private final RootProvider provider;
		
		private final ObjectID                       id;
		private final UnsafeSupplier<T, IOException> objectGenerator;
		
		public Builder(RootProvider provider){
			this.provider=provider;
			id=null;
			objectGenerator=null;
		}
		
		private Builder(RootProvider provider, ObjectID id, UnsafeSupplier<T, IOException> objectGenerator){
			this.provider=provider;
			this.id=id;
			this.objectGenerator=objectGenerator;
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
		public <CT> Builder<? extends CT> withType(Class<CT> genericType, Type... args){return (Builder<CT>)withType(TypeLink.of(genericType, args));}
		@SuppressWarnings("unchecked")
		public <CT> Builder<CT> withType(Class<CT> genericType){return (Builder<CT>)withType(TypeLink.of(genericType));}
		public Builder<T> withType(Type genericType){return withType(Objects.requireNonNull(TypeLink.of(genericType)));}
		@SuppressWarnings("unchecked")
		public Builder<T> withType(TypeLink genericType){
			var provider=this.provider.getDataProvider();
			var rawType =genericType.getTypeClass(provider.getTypeDb());
			
			var p=SupportedPrimitive.get(rawType).map(typ->withGenerator(()->(T)typ.getDefaultValue()));
			if(p.isPresent()) return p.get();
			
			if(IOInstance.isInstance(rawType)){
				var struct=Struct.ofUnknown(rawType);
				
				if(struct instanceof Struct.Unmanaged<?> uStruct){
					return withGenerator(()->{
						var pipe=ContiguousStructPipe.of(struct);
						var siz =pipe.getSizeDescriptor().calcAllocSize(WordSpace.BYTE);
						
						var mem=AllocateTicket.bytes(siz).submit(provider);
						
						var inst=uStruct.getUnmanagedConstructor().create(provider, mem.getPtr().makeReference(), genericType);
						return (T)inst;
					});
				}else{
					return withGenerator(()->{
						var inst=struct.emptyConstructor().get();
						if(struct.hasInvalidInitialNulls()){
							inst.allocateNulls(provider);
						}
						return (T)inst;
					});
				}
			}
			
			throw new IllegalArgumentException("Unrecognised type: "+rawType.getSimpleName()+" in "+genericType);
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
	
	default <T extends IOInstance<T>> T request(Struct<T> type, String id) throws IOException{return this.builder().withId(id).withType(type.getType()).request();}
	default <T> T request(Class<T> type, String id) throws IOException                       {return this.builder().withId(id).withType(type).request();}
	
	<T> T request(ObjectID id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException;
	<T> void provide(T obj, ObjectID id) throws IOException;
	
	
	Iterable<IOMap.Entry<ObjectID, Object>> listAll();
	
}
