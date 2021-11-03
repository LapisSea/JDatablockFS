package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

public class IOFieldInstanceArray<T extends IOInstance<T>, ValType extends IOInstance<ValType>> extends IOField<T, ValType[]>{
	
	private final SizeDescriptor<T>   descriptor;
	private final Class<ValType>      component;
	private       IOField<T, Integer> arraySize;
	
	private StructPipe<ValType> valPipe;
	
	public IOFieldInstanceArray(FieldAccessor<T> accessor){
		super(accessor);
		var type=accessor.getType();
		
		component=(Class<ValType>)type.getComponentType();
		if(component.isArray()) throw new MalformedStructLayout("Multi dimension arrays are not supported (yet)");
		
		descriptor=new SizeDescriptor.Unknown<>(0, OptionalLong.empty(), inst->{
			var siz=getArraySize(inst);
			if(siz==0) return 0;
			
			var arr=get(inst);
			
			var desc=getValPipe().getSizeDescriptor();
			if(desc.hasFixed()){
				return arr.length*desc.requireFixed(WordSpace.BYTE);
			}
			return Arrays.stream(arr).mapToLong(desc::calcUnknown).sum();
		});
	}
	
	private StructPipe<ValType> getValPipe(){
		if(valPipe==null) valPipe=ContiguousStructPipe.of(component);
		return valPipe;
	}
	
	@Override
	public void init(){
		super.init();
		arraySize=declaringStruct().getFields().requireExact(Integer.class, IOFieldTools.makeArrayLenName(getAccessor()));
	}
	
	private int getArraySize(T inst){
		return arraySize.get(inst);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		var pip=getValPipe();
		
		var arr=get(instance);
		for(ValType el : arr){
			pip.write(provider, dest, el);
		}
		return List.of();
	}
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var pip=getValPipe();
		
		int       size=getArraySize(instance);
		ValType[] data=(ValType[])Array.newInstance(component, size);
		for(int i=0;i<size;i++){
			data[i]=pip.readNew(provider, src, genericContext);
		}
		set(instance, data);
	}
	
	@Override
	public void skipRead(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var pip=getValPipe();
		
		int     size=getArraySize(instance);
		ValType inst=pip.getType().requireEmptyConstructor().get();
		for(int i=0;i<size;i++){
			pip.read(provider, src, inst, genericContext);
		}
	}
}
