package com.lapissea.cfs.type.field.fields;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.config.GlobalConfig.TYPE_VALIDATION;
import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static com.lapissea.cfs.type.StagedInit.runBaseStageTask;

public abstract sealed class CollectionAddapter<ElementType, CollectionType>{
	
	public interface ElementIOImpl<E>{
		
		final class PipeImpl<E extends IOInstance<E>> implements ElementIOImpl<E>{
			
			private       StructPipe<E> pipe;
			private final Class<E>      componentType;
			
			public PipeImpl(Class<E> componentType){
				this.componentType = componentType;
				if(!IOInstance.isInstance(componentType)) throw new MalformedStruct(componentType + " is not an IOInstance");
				if(IOInstance.isUnmanaged(componentType)) throw new MalformedStruct(componentType + " is unmanaged");
				
				try{
					//preload pipe
					if(TYPE_VALIDATION){
						runBaseStageTask(this::getPipe);
					}else{
						StandardStructPipe.of(componentType);
					}
				}catch(RecursiveSelfCompilation e){
					Log.debug("recursive compilation for {}", componentType);
				}
			}
			
			private StructPipe<E> getPipe(){
				if(pipe == null){
					pipe = StandardStructPipe.of(componentType, STATE_DONE);
				}
				return pipe;
			}
			
			@Override
			public Class<E> componentType(){
				return componentType;
				
			}
			@Override
			public long calcByteSize(DataProvider provider, E element){
				return getPipe().calcUnknownSize(provider, element, WordSpace.BYTE);
			}
			@Override
			public OptionalLong getFixedByteSize(){
				return getPipe().getSizeDescriptor().getFixed(WordSpace.BYTE);
			}
			@Override
			public void write(DataProvider provider, ContentWriter dest, E element) throws IOException{
				getPipe().write(provider, dest, element);
			}
			@Override
			public E read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				return getPipe().readNew(provider, src, genericContext);
			}
			@Override
			public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				getPipe().skip(provider, src, genericContext);
			}
		}
		
		Class<E> componentType();
		long calcByteSize(DataProvider provider, E element);
		OptionalLong getFixedByteSize();
		void write(DataProvider provider, ContentWriter dest, E element) throws IOException;
		E read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException;
		void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException;
	}
	
	public static final class OfArray<E> extends CollectionAddapter<E, E[]>{
		
		public static Class<?> getComponentType(Type type){
			var raw  = Utils.typeToRaw(type);
			var comp = raw.componentType();
			if(comp == null) throw new ShouldNeverHappenError();
			if(comp.isArray()) throw new MalformedStruct(type + " is multi dimensional array");
			return comp;
		}
		
		public OfArray(ElementIOImpl<E> elementIO){ super(elementIO); }
		
		@Override
		public int getSize(E[] collection){ return collection.length; }
		@Override
		public void setElement(E[] collection, int index, E element){ collection[index] = element; }
		@SuppressWarnings("unchecked")
		@Override
		public E[] makeNew(int size){ return (E[])Array.newInstance(getElementIO().componentType(), size); }
		@Override
		public Collection<E> getAsCollection(E[] collection){ return Arrays.asList(collection); }
	}
	
	public static final class OfList<E> extends CollectionAddapter<E, List<E>>{
		public static Class<?> getComponentType(Type type){
			var parmType = (ParameterizedType)type;
			return Utils.typeToRaw(parmType.getActualTypeArguments()[0]);
		}
		
		public OfList(ElementIOImpl<E> elementIO){ super(elementIO); }
		
		@Override
		public int getSize(List<E> collection){ return collection.size(); }
		
		@Override
		public void setElement(List<E> collection, int index, E element){
			if(index == collection.size()){
				collection.add(element);
				return;
			}
			collection.set(index, element);
		}
		@Override
		public List<E> makeNew(int size){
			var l = new ArrayList<E>();
			l.ensureCapacity(size);
			return l;
		}
		@Override
		public Collection<E> getAsCollection(List<E> collection){ return collection; }
	}
	
	@SuppressWarnings("rawtypes")
	public static Class<?> getComponentType(Class<? extends CollectionAddapter> dataAdapterType, Type type){
		if(UtilL.instanceOf(dataAdapterType, OfArray.class)) return OfArray.getComponentType(type);
		if(UtilL.instanceOf(dataAdapterType, OfList.class)) return OfList.getComponentType(type);
		throw new ShouldNeverHappenError();
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <E, C> CollectionAddapter<E, C> newAddapter(Class<? extends CollectionAddapter> type, ElementIOImpl<E> elementIO){
		if(UtilL.instanceOf(type, OfArray.class)) return (CollectionAddapter<E, C>)new OfArray<>(elementIO);
		if(UtilL.instanceOf(type, OfList.class)) return (CollectionAddapter<E, C>)new OfList<>(elementIO);
		throw new ShouldNeverHappenError();
	}
	
	public CollectionAddapter(ElementIOImpl<ElementType> elementIO){
		this.elementIO = elementIO;
	}
	
	public void write(CollectionType arr, DataProvider provider, ContentWriter dest) throws IOException{
		
		for(ElementType el : getAsCollection(arr)){
			if(DEBUG_VALIDATION){
				var siz = elementIO.calcByteSize(provider, el);
				
				try(var buff = dest.writeTicket(siz).requireExact().submit()){
					elementIO.write(provider, buff, el);
				}
			}else{
				elementIO.write(provider, dest, el);
			}
		}
	}
	
	public CollectionType read(int size, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var data = makeNew(size);
		for(int i = 0; i<size; i++){
			setElement(data, i, elementIO.read(provider, src, genericContext));
		}
		return data;
	}
	
	public void skipData(int size, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var fixed = elementIO.getFixedByteSize();
		if(fixed.isPresent()){
			src.skipExact(size*fixed.getAsLong());
		}else{
			for(int i = 0; i<size; i++){
				elementIO.skip(provider, src, genericContext);
			}
		}
	}
	
	private final ElementIOImpl<ElementType> elementIO;
	
	public final ElementIOImpl<ElementType> getElementIO(){
		return elementIO;
	}
	
	public abstract int getSize(CollectionType collection);
	
	public abstract void setElement(CollectionType collection, int index, ElementType element);
	
	public abstract CollectionType makeNew(int size);
	
	public abstract Collection<ElementType> getAsCollection(CollectionType collection);
	
}