package com.lapissea.dfs.type.field.fields;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.StagedInit.STATE_DONE;

public abstract sealed class CollectionAdapter<ElementType, CollectionType>{
	
	public interface ElementIOImpl<E>{
		
		final class PipeImpl<E extends IOInstance<E>> implements ElementIOImpl<E>{
			
			private final StructPipe<E> pipe;
			private final Class<E>      componentType;
			
			public PipeImpl(Class<E> componentType){
				this.componentType = componentType;
				if(!IOInstance.isInstance(componentType)) throw new MalformedStruct("fmt", "{}#red is not an IOInstance", componentType);
				if(IOInstance.isUnmanaged(componentType)) throw new MalformedStruct("fmt", "{}#red is unmanaged", componentType);
				
				pipe = StandardStructPipe.of(componentType, STATE_DONE);
			}
			
			@Override
			public Class<E> componentType(){
				return componentType;
			}
			
			@Override
			public long calcByteSize(DataProvider provider, E element){
				return pipe.calcUnknownSize(provider, element, WordSpace.BYTE);
			}
			@Override
			public OptionalLong getFixedByteSize(){
				return pipe.getSizeDescriptor().getFixed(WordSpace.BYTE);
			}
			@Override
			public void write(DataProvider provider, ContentWriter dest, E element) throws IOException{
				pipe.write(provider, dest, element);
			}
			@Override
			public E read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				return pipe.readNew(provider, src, genericContext);
			}
			@Override
			public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				pipe.skip(provider, src, genericContext);
			}
		}
		
		final class SealedTypeImpl<E extends IOInstance<E>> implements ElementIOImpl<E>{
			
			private final Class<E>                     root;
			private final Map<Class<E>, StructPipe<E>> pipeMap;
			
			public SealedTypeImpl(SealedUtil.SealedInstanceUniverse<E> universe){
				root = universe.root();
				pipeMap = universe.pipeMap();
				for(var componentType : pipeMap.keySet()){
					if(!IOInstance.isInstance(componentType)) throw new MalformedStruct("fmt", "{}#red is not an IOInstance", componentType);
					if(IOInstance.isUnmanaged(componentType)) throw new MalformedStruct("fmt", "{}#red is unmanaged", componentType);
				}
			}
			
			private StructPipe<E> getPipe(E element){
				return pipeMap.get(element.getClass());
			}
			private StructPipe<E> getPipe(Class<E> type){
				return pipeMap.get(type);
			}
			
			@Override
			public Class<E> componentType(){
				return root;
				
			}
			@Override
			public long calcByteSize(DataProvider provider, E element){
				if(element == null) return 1;
				var pip = getPipe(element);
				int id;
				try{
					id = provider.getTypeDb().toID(root, (Class<E>)element.getClass(), false);
				}catch(IOException e){
					throw new RuntimeException("Failed to compute ID", e);
				}
				return 1 + NumberSize.bySize(id).bytes + pip.calcUnknownSize(provider, element, WordSpace.BYTE);
			}
			@Override
			public OptionalLong getFixedByteSize(){
				//Empty even if all children are the same fixed size because children may be changed and new IDs introduced.
				// This gives a chance that the ID size may grow. TODO: Solution may be to restrict sealed type ID to a u16
				return OptionalLong.empty();
			}
			@Override
			public void write(DataProvider provider, ContentWriter dest, E element) throws IOException{
				int id = 0;
				if(element != null){
					//noinspection unchecked
					var type = (Class<E>)element.getClass();
					id = provider.getTypeDb().toID(root, type, true);
				}
				dest.writeInt4Dynamic(id);
				if(id != 0){
					var pip = getPipe(element);
					pip.write(provider, dest, element);
				}
			}
			@Override
			public E read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				var id = src.readInt4Dynamic();
				if(id == 0) return null;
				var type = provider.getTypeDb().fromID(root, id);
				return getPipe(type).readNew(provider, src, genericContext);
			}
			@Override
			public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				var id = src.readInt4Dynamic();
				if(id == 0) return;
				var type = provider.getTypeDb().fromID(root, id);
				getPipe(type).skip(provider, src, genericContext);
			}
		}
		
		Class<E> componentType();
		long calcByteSize(DataProvider provider, E element);
		OptionalLong getFixedByteSize();
		void write(DataProvider provider, ContentWriter dest, E element) throws IOException;
		E read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException;
		void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException;
	}
	
	public static final class OfArray<E> extends CollectionAdapter<E, E[]>{
		
		public static Class<?> getComponentType(Type type){
			var raw  = Utils.typeToRaw(type);
			var comp = raw.componentType();
			if(comp == null) throw new ShouldNeverHappenError();
			if(comp.isArray()) throw new MalformedStruct("fmt", "{}#red is multi dimensional array", type);
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
		public E[] asCollection(E[] data){
			return data;
		}
		@Override
		public List<E> asListView(E[] collection){ return Arrays.asList(collection); }
	}
	
	public static final class OfList<E> extends CollectionAdapter<E, List<E>>{
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
		public List<E> asCollection(E[] data){
			return new ArrayList<>(Arrays.asList(data));
		}
		@Override
		public List<E> asListView(List<E> collection){ return collection; }
	}
	
	@SuppressWarnings("rawtypes")
	public static Class<?> getComponentType(Class<? extends CollectionAdapter> dataAdapterType, Type type){
		if(UtilL.instanceOf(dataAdapterType, OfArray.class)) return OfArray.getComponentType(type);
		if(UtilL.instanceOf(dataAdapterType, OfList.class)) return OfList.getComponentType(type);
		throw new ShouldNeverHappenError();
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <E, C> CollectionAdapter<E, C> newAdapter(Class<? extends CollectionAdapter> type, ElementIOImpl<E> elementIO){
		if(UtilL.instanceOf(type, OfArray.class)) return (CollectionAdapter<E, C>)new OfArray<>(elementIO);
		if(UtilL.instanceOf(type, OfList.class)) return (CollectionAdapter<E, C>)new OfList<>(elementIO);
		throw new ShouldNeverHappenError();
	}
	
	public CollectionAdapter(ElementIOImpl<ElementType> elementIO){
		this.elementIO = elementIO;
	}
	
	public void write(CollectionType arr, DataProvider provider, ContentWriter dest) throws IOException{
		
		for(ElementType el : asListView(arr)){
			if(DEBUG_VALIDATION){
				var buff = makeSizedBuff(provider, dest, el);
				elementIO.write(provider, buff, el);
				buff.close(); // do not use finally because if there was an error, we do not want to submit the partial data anyways
			}else{
				elementIO.write(provider, dest, el);
			}
		}
	}
	
	private ContentWriter.BufferTicket.WriteArrayBuffer makeSizedBuff(DataProvider provider, ContentWriter dest, ElementType el){
		var fixed = elementIO.getFixedByteSize();
		var siz   = fixed.isPresent()? fixed.getAsLong() : elementIO.calcByteSize(provider, el);
		return dest.writeTicket(siz).requireExact().submit();
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
	public abstract CollectionType asCollection(ElementType[] data);
	
	public abstract List<ElementType> asListView(CollectionType collection);
	
}
