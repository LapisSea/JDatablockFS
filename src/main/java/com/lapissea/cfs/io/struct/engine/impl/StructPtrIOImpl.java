package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.AllocateTicket;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ObjectPointer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

@SuppressWarnings("unchecked")
public class StructPtrIOImpl<T extends IOInstance&SelfPoint<T>> extends VariableNode.SelfPointer<T>{
	
	public static class Fixed<T extends IOInstance&SelfPoint<T>> extends StructPtrIOImpl<T> implements VariableNode.FixedSize{
		
		public Fixed(String name, int index,
		             Field valueField, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun, IOStruct.Construct.Constructor<T> constructorFun,
		             ReaderWriter<ObjectPointer<T>> ptrIO, IOStruct structType){
			super(name, index, valueField, getFun, setFun, constructorFun, ptrIO, structType);
		}
		
		@Override
		public long getSize(){
			return ptrIO.getFixedSize().orElseThrow();
		}
		
		@Override
		protected long mapSize(IOInstance target, T value){
			return getSize();
		}
		
		@Override
		public long mapSize(IOInstance target){
			return getSize();
		}
	}
	
	private final Field                             valueField;
	private final IOStruct.Get.Getter<T>            getFun;
	private final IOStruct.Set.Setter<T>            setFun;
	private final IOStruct.Construct.Constructor<T> constructorFun;
	
	protected final ReaderWriter<ObjectPointer<T>> ptrIO;
	
	private final IOStruct structType;
	
	
	public StructPtrIOImpl(String name, int index,
	                       Field valueField, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun, IOStruct.Construct.Constructor<T> constructorFun,
	                       ReaderWriter<ObjectPointer<T>> ptrIO, IOStruct structType){
		super(name, index);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.constructorFun=constructorFun;
		this.ptrIO=ptrIO;
		this.structType=structType;
	}
	
	@Override
	protected T getValue(IOInstance source){
		if(getFun==null){
			try{
				return (T)valueField.get(source);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}else{
			return getFun.getValue(source);
		}
	}
	
	@Override
	protected void setValue(IOInstance target, T newValue){
		if(setFun==null){
			try{
				valueField.set(target, newValue);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}else{
			setFun.setValue(target, newValue);
		}
	}
	
	@Override
	protected long mapSize(IOInstance target, T value){
		var siz=ptrIO.getFixedSize();
		if(siz.isPresent()) return siz.getAsInt();
		return ptrIO.mapSize(target, value.getSelfPtr());
	}
	
	@Override
	protected T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
		ObjectPointer<T> ptr=ptrIO.read(target, source, new ObjectPointer.Struct<>(null));
		if(ptr.getDataBlock()==null) return null;
		Chunk c=ptr.getBlock(cluster);
		T     val;
		if(oldVal==null){
			val=newInstance(target, c);
		}else{
			val=oldVal;
		}
		
		c.ioAt(ptr.getOffset(), io->{val.readStruct(cluster, io);});
		
		return val;
	}
	
	@Override
	protected void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException{
		ObjectPointer<T> ptr;
		if(source==null) ptr=null;
		else{
			ptr=source.getSelfPtr();
			
			if(ptr.getDataBlock()==null){
//				ptr=cluster.alloc(source.getInstanceSize()).getPtr();
				throw new IllegalStateException();
			}
			
			ptr.write(cluster, source);
		}
		
		ptrIO.write(target, dest, ptr);
	}
	
	@Override
	public OptionalLong getMaximumSize(){
		return ptrIO.getMaxSize().isPresent()?OptionalLong.of(ptrIO.getMaxSize().getAsInt()):OptionalLong.empty();
	}
	
	private T newInstance(IOInstance target, Chunk chunk) throws IOException{
		if(constructorFun!=null){
			return constructorFun.construct(target, chunk);
		}else{
			return structType.newInstance(target, chunk);
		}
	}
	
	@Override
	public void allocNew(IOInstance target, Cluster cluster) throws IOException{
		allocNew(target, cluster, structType.getKnownSize().isPresent()||structType.getMaximumSize().isPresent());
	}
	
	@Override
	public void allocNew(IOInstance target, Cluster cluster, boolean disableNext) throws IOException{
		Chunk chunk=AllocateTicket.bytes(structType.getKnownSize().orElse(structType.getMaximumSize().orElse(structType.getMinimumSize())))
		                          .shouldDisableResizing(disableNext)
		                          .submit(cluster);
		
		setValue(target, newInstance(target, chunk));
		
		chunk.io(io->{
			getValue(target).writeStruct(cluster, io);
			io.trim();
		});
	}
}
